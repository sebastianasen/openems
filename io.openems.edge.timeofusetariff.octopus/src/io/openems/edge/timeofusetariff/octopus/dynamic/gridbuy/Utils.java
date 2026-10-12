package io.openems.edge.timeofusetariff.octopus.dynamic.gridbuy;

import static io.openems.common.utils.JsonUtils.getAsDouble;
import static io.openems.common.utils.JsonUtils.getAsJsonArray;
import static io.openems.common.utils.JsonUtils.getAsJsonObject;
import static io.openems.common.utils.JsonUtils.getAsString;
import static io.openems.common.utils.JsonUtils.parseToJsonObject;
import static java.time.format.DateTimeFormatter.ISO_DATE_TIME;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSortedMap;
import com.google.gson.JsonArray;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.timeofusetariff.api.TimeOfUsePrices;

final class Utils {

	private static final Logger LOG = LoggerFactory.getLogger(Utils.class);

	private static final int RETRY_AFTER_RATE_LIMIT_EXCEEDED_HOURS = 12;
	private static final int RETRY_AFTER_UNABLE_TO_UPDATE_PRICES_MINUTES = 5;
	private static final Random RANDOM = new Random();

	// Kraken API: price is in cents/kWh; multiply by 10 to get EUR/MWh
	private static final double CENTS_PER_KWH_TO_EUR_PER_MWH = 10.0;

	private Utils() {
	}

	/**
	 * Generates the GraphQL mutation body to obtain a Kraken API token.
	 *
	 * @param email    the account email
	 * @param password the account password
	 * @return GraphQL mutation JSON body string
	 */
	static String generateTokenQuery(String email, String password) {
		// Escape special chars for JSON string literals
		var safeEmail = email.replace("\\", "\\\\").replace("\"", "\\\"");
		var safePassword = password.replace("\\", "\\\\").replace("\"", "\\\"");
		return "{\"query\":\"mutation { obtainKrakenToken(input: { email: \\\"" + safeEmail + "\\\", password: \\\""
				+ safePassword + "\\\" }) { token } }\"}";
	}

	/**
	 * Parses the token from the obtainKrakenToken mutation response.
	 *
	 * @param jsonData JSON response string
	 * @return the token
	 * @throws OpenemsNamedException on parse error
	 */
	static String parseToken(String jsonData) throws OpenemsNamedException {
		return getAsString(//
				getAsJsonObject(//
						getAsJsonObject(//
								parseToJsonObject(jsonData), //
								"data"), //
						"obtainKrakenToken"), //
				"token");
	}

	/**
	 * Generates the GraphQL query body to fetch day-ahead prices for an account.
	 *
	 * @param accountNumber the Octopus account number (e.g. A-01F24F4E)
	 * @return GraphQL query JSON body string
	 */
	static String generatePriceQuery(String accountNumber) {
		var safeAccount = accountNumber.replace("\\", "\\\\").replace("\"", "\\\"");
		return "{\"query\":\"query { account(accountNumber: \\\"" + safeAccount + "\\\") { "
				+ "properties { electricityMalos { agreements { unitRateForecast { "
				+ "validFrom validTo unitRateInformation { "
				+ "... on TimeOfUseProductUnitRateInformation { rates { latestGrossUnitRateCentsPerKwh } } "
				+ "} } } } } } }\"}";
	}

	/**
	 * Parses the Octopus Kraken API JSON response to {@link TimeOfUsePrices}.
	 *
	 * <p>
	 * Iterates all properties → electricityMalos → agreements → unitRateForecast
	 * and collects all non-empty forecasts. The price used is
	 * {@code latestGrossUnitRateCentsPerKwh} converted to EUR/MWh.
	 *
	 * @param jsonData the JSON response string
	 * @return the parsed {@link TimeOfUsePrices}
	 * @throws OpenemsNamedException on parse or data error
	 */
	static TimeOfUsePrices parsePrices(String jsonData) throws OpenemsNamedException {
		var account = getAsJsonObject(//
				getAsJsonObject(//
						parseToJsonObject(jsonData), //
						"data"), //
				"account");

		var properties = getAsJsonArray(account, "properties");
		var result = ImmutableSortedMap.<Instant, Double>naturalOrder();
		var foundAnySlot = false;

		for (var property : properties) {
			var electricityMalos = getAsJsonArray(property, "electricityMalos");
			for (var malo : electricityMalos) {
				var agreements = getAsJsonArray(malo, "agreements");
				for (var agreement : agreements) {
					var forecast = getAsJsonArray(agreement, "unitRateForecast");
					if (forecast.isEmpty()) {
						continue;
					}
					parseUnitRateForecast(forecast.getAsJsonArray(), result);
					foundAnySlot = true;
				}
			}
		}

		if (!foundAnySlot) {
			throw new OpenemsException("No unitRateForecast data found in Octopus API response");
		}

		return TimeOfUsePrices.from(result.build());
	}

	/**
	 * Parses a unitRateForecast array and fills the result map.
	 *
	 * @param forecast unitRateForecast JSON array
	 * @param result   the map to fill
	 * @throws OpenemsNamedException on parse error
	 */
	private static void parseUnitRateForecast(JsonArray forecast, ImmutableSortedMap.Builder<Instant, Double> result)
			throws OpenemsNamedException {
		for (var slot : forecast) {
			var unitRateInfo = getAsJsonObject(slot, "unitRateInformation");
			// The fragment ... on TimeOfUseProductUnitRateInformation adds "rates"
			if (!unitRateInfo.has("rates")) {
				continue;
			}
			var rates = getAsJsonArray(unitRateInfo, "rates");
			if (rates.isEmpty()) {
				continue;
			}
			var grossCentsPerKwh = getAsDouble(rates.get(0), "latestGrossUnitRateCentsPerKwh");
			var priceEurPerMwh = grossCentsPerKwh * CENTS_PER_KWH_TO_EUR_PER_MWH;
			var validFrom = ZonedDateTime.parse(getAsString(slot, "validFrom"), ISO_DATE_TIME).toInstant();
			result.put(validFrom, priceEurPerMwh);
		}
	}

	/**
	 * Calculates the delay until the next scheduled task run.
	 *
	 * @param httpStatusCode       the HTTP status code
	 * @param unableToUpdatePrices whether prices could not be updated
	 * @return delay in seconds
	 */
	static long calculateDelay(int httpStatusCode, boolean unableToUpdatePrices) {
		final var now = ZonedDateTime.now();
		final ZonedDateTime nextRun;

		if (httpStatusCode == TariffOctopusDynamicGridBuyImpl.TOO_MANY_REQUESTS_CODE) {
			LOG.warn("Rate limit exceeded. Retrying after " + RETRY_AFTER_RATE_LIMIT_EXCEEDED_HOURS + " hours.");
			nextRun = now.plusHours(RETRY_AFTER_RATE_LIMIT_EXCEEDED_HOURS);

		} else if (httpStatusCode == TariffOctopusDynamicGridBuyImpl.CLIENT_ERROR_CODE) {
			// Authentication failed – do not retry automatically
			return 0;

		} else if (unableToUpdatePrices) {
			nextRun = now.plusMinutes(RETRY_AFTER_UNABLE_TO_UPDATE_PRICES_MINUTES).truncatedTo(ChronoUnit.MINUTES);
			LOG.warn("Unable to update Octopus prices. Retrying in {} minutes at {}",
					RETRY_AFTER_UNABLE_TO_UPDATE_PRICES_MINUTES, nextRun);

		} else {
			// Successful – retry at the start of the next hour
			nextRun = now.truncatedTo(ChronoUnit.HOURS).plusHours(1);
		}

		return Duration.between(now, nextRun.plusSeconds(RANDOM.nextInt(60))).getSeconds();
	}
}

