package io.openems.edge.timeofusetariff.octopus.dynamic.gridbuy;

import static io.openems.edge.common.channel.ChannelUtils.setValue;
import static io.openems.edge.timeofusetariff.api.utils.TimeOfUseTariffUtils.generateDebugLog;
import static io.openems.edge.timeofusetariff.octopus.dynamic.gridbuy.Utils.calculateDelay;
import static io.openems.edge.timeofusetariff.octopus.dynamic.gridbuy.Utils.generatePriceQuery;
import static io.openems.edge.timeofusetariff.octopus.dynamic.gridbuy.Utils.generateTokenQuery;
import static io.openems.edge.timeofusetariff.octopus.dynamic.gridbuy.Utils.parsePrices;
import static io.openems.edge.timeofusetariff.octopus.dynamic.gridbuy.Utils.parseToken;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.utils.ThreadPoolUtils;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.meta.Meta;
import io.openems.edge.timeofusetariff.api.TimeOfUsePrices;
import io.openems.edge.timeofusetariff.api.TimeOfUseTariff;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Tariff.Octopus.Dynamic.GridBuy", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class TariffOctopusDynamicGridBuyImpl extends AbstractOpenemsComponent
		implements TimeOfUseTariff, OpenemsComponent, TariffOctopusDynamicGridBuy {

	protected static final String KRAKEN_API_URL = "https://api.oeg-kraken.energy/v1/graphql/";
	protected static final int CLIENT_ERROR_CODE = 400;
	protected static final int TOO_MANY_REQUESTS_CODE = 429;

	private static final MediaType JSON = MediaType.parse("application/json");

	private final Logger log = LoggerFactory.getLogger(TariffOctopusDynamicGridBuyImpl.class);
	private OkHttpClient client;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	private final AtomicReference<TimeOfUsePrices> prices = new AtomicReference<>(TimeOfUsePrices.EMPTY_PRICES);

	/** Cached Kraken API token and its expiry (conservative: subtract 60 s). */
	private volatile String cachedToken = null;
	private volatile Instant tokenExpiresAt = Instant.EPOCH;

	@Reference
	private Meta meta;

	@Reference
	private ComponentManager componentManager;

	private Config config = null;

	public TariffOctopusDynamicGridBuyImpl() {
		this(new OkHttpClient());
	}

	TariffOctopusDynamicGridBuyImpl(OkHttpClient client) {
		super(//
				OpenemsComponent.ChannelId.values(), //
				TariffOctopusDynamicGridBuy.ChannelId.values() //
		);
		this.client = client;
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;

		if (!config.enabled()) {
			return;
		}
		this.executor.schedule(this.task, 0, TimeUnit.SECONDS);
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
		ThreadPoolUtils.shutdownAndAwaitTermination(this.executor, 0);
	}

	protected final Runnable task = () -> {
		var httpStatusCode = 0;
		var unableToUpdatePrices = false;

		try {
			var token = this.getValidToken();

			// Fetch day-ahead prices
			var priceRequest = new Request.Builder() //
					.url(KRAKEN_API_URL) //
					.header("Authorization", token) //
					.post(RequestBody.create(generatePriceQuery(this.config.accountNumber()), JSON)) //
					.build();

			try (var response = this.client.newCall(priceRequest).execute()) {
				httpStatusCode = response.code();

				if (!response.isSuccessful()) {
					throw new HttpStatusCodeException(response.code(), "Unexpected HTTP code " + response.code());
				}

				this.prices.set(parsePrices(response.body().string()));
			}

		} catch (HttpStatusCodeException e) {
			httpStatusCode = e.getStatusCode();
			unableToUpdatePrices = true;
			this.logWarn(this.log, "Failed to update Octopus prices: " + e.getMessage());
		} catch (IOException | OpenemsNamedException e) {
			unableToUpdatePrices = true;
			this.logWarn(this.log, "Failed to update Octopus prices: " + e.getMessage());
		}

		this.setChannelValues(httpStatusCode);

		var delay = calculateDelay(httpStatusCode, unableToUpdatePrices);
		if (delay != 0) {
			this.executor.schedule(this.task, delay, TimeUnit.SECONDS);
		}
	};

	/**
	 * Returns a valid Kraken API token, fetching a new one if the cached token is
	 * expired or absent.
	 *
	 * @return a valid JWT token string
	 * @throws IOException           on HTTP error
	 * @throws OpenemsNamedException on JSON parse error
	 */
	private String getValidToken() throws IOException, OpenemsNamedException {
		if (this.cachedToken != null && Instant.now().isBefore(this.tokenExpiresAt)) {
			return this.cachedToken;
		}

		var tokenRequest = new Request.Builder() //
				.url(KRAKEN_API_URL) //
				.post(RequestBody.create(//
						generateTokenQuery(this.config.email(), this.config.password()), JSON)) //
				.build();

		try (var response = this.client.newCall(tokenRequest).execute()) {
			if (!response.isSuccessful()) {
				throw new HttpStatusCodeException(response.code(), "Token request failed with HTTP " + response.code());
			}
			var token = parseToken(response.body().string());
			// Tokens are valid for 1 hour; cache them with a 60 s safety margin
			this.cachedToken = token;
			this.tokenExpiresAt = Instant.now().plusSeconds(3600 - 60L);
			return token;
		}
	}

	private static class HttpStatusCodeException extends IOException {

		private static final long serialVersionUID = 1L;
		private final int statusCode;

		private HttpStatusCodeException(int statusCode, String message) {
			super(message);
			this.statusCode = statusCode;
		}

		private int getStatusCode() {
			return this.statusCode;
		}
	}

	/**
	 * Sets channel values based on the HTTP status code.
	 *
	 * @param httpStatusCode the HTTP status code
	 */
	private void setChannelValues(int httpStatusCode) {
		var authenticationFailed = false;
		var serverError = false;
		var timeout = false;

		switch (httpStatusCode) {
		case CLIENT_ERROR_CODE:
			authenticationFailed = true;
			this.logWarn(this.log, "Authentication failed. Please check email and password.");
			break;

		case TOO_MANY_REQUESTS_CODE:
			timeout = true;
			break;

		default:
			if (httpStatusCode >= 200 && httpStatusCode < 300) {
				// Success – no error
			} else if (httpStatusCode != 0) {
				serverError = true;
				this.logWarn(this.log, "Unexpected server error. HTTP status: " + httpStatusCode);
			}
			break;
		}

		setValue(this, TariffOctopusDynamicGridBuy.ChannelId.HTTP_STATUS_CODE, httpStatusCode);
		setValue(this, TariffOctopusDynamicGridBuy.ChannelId.STATUS_TIMEOUT, timeout);
		setValue(this, TariffOctopusDynamicGridBuy.ChannelId.STATUS_AUTHENTICATION_FAILED, authenticationFailed);
		setValue(this, TariffOctopusDynamicGridBuy.ChannelId.STATUS_SERVER_ERROR, serverError);
	}

	@Override
	public TimeOfUsePrices getPrices() {
		return TimeOfUsePrices.from(Instant.now(this.componentManager.getClock()), this.prices.get());
	}

	@Override
	public String debugLog() {
		return generateDebugLog(this, this.meta.getCurrency());
	}
}




