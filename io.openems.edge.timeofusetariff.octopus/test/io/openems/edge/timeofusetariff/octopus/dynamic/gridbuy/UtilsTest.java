package io.openems.edge.timeofusetariff.octopus.dynamic.gridbuy;

import static io.openems.edge.timeofusetariff.octopus.dynamic.gridbuy.Data.JSON_PRICES;
import static io.openems.edge.timeofusetariff.octopus.dynamic.gridbuy.Data.JSON_TOKEN;
import static io.openems.edge.timeofusetariff.octopus.dynamic.gridbuy.TariffOctopusDynamicGridBuyImpl.CLIENT_ERROR_CODE;
import static io.openems.edge.timeofusetariff.octopus.dynamic.gridbuy.TariffOctopusDynamicGridBuyImpl.TOO_MANY_REQUESTS_CODE;
import static io.openems.edge.timeofusetariff.octopus.dynamic.gridbuy.Utils.calculateDelay;
import static io.openems.edge.timeofusetariff.octopus.dynamic.gridbuy.Utils.parsePrices;
import static io.openems.edge.timeofusetariff.octopus.dynamic.gridbuy.Utils.parseToken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;

class UtilsTest {

	private static final int SUCCESS_CODE = 200;
	private static final int SERVER_ERROR_CODE = 500;

	@Test
	void parsePricesTest() throws OpenemsNamedException {
		var prices = parsePrices(JSON_PRICES);

		assertFalse(prices.isEmpty());

		// First slot: 33.12841 cents/kWh * 10 = 331.2841 EUR/MWh
		assertEquals(331.2841, prices.getFirst(), 0.001);
	}

	@Test
	void parsePricesEmptyTest() {
		assertThrows(OpenemsNamedException.class, () -> parsePrices(""));
	}

	@Test
	void parseTokenTest() throws OpenemsNamedException {
		var token = parseToken(JSON_TOKEN);
		assertEquals("test-token-value", token);
	}

	@Test
	void calculateDelaySuccessTest() {
		var delay = calculateDelay(SUCCESS_CODE, false);
		// Next run is at the start of the next hour: 0..3660 seconds
		assertTrue(delay >= 0 && delay <= 3660);
	}

	@Test
	void calculateDelayClientErrorTest() {
		// Authentication failed: no retry
		var delay = calculateDelay(CLIENT_ERROR_CODE, true);
		assertEquals(0, delay);
	}

	@Test
	void calculateDelayRateLimitTest() {
		var delay = calculateDelay(TOO_MANY_REQUESTS_CODE, true);
		// 12 hours = 43200 s, plus up to 60 s random
		assertTrue(delay >= 43200 && delay <= 43260);
	}

	@Test
	void calculateDelayServerErrorTest() {
		var delay = calculateDelay(SERVER_ERROR_CODE, true);
		// Retry in 5 minutes ± truncate/random: 240..360 s
		assertTrue(delay >= 240 && delay <= 360);
	}
}

