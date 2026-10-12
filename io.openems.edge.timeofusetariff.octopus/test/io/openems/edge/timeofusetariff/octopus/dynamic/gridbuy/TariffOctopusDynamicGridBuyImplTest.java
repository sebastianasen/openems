package io.openems.edge.timeofusetariff.octopus.dynamic.gridbuy;

import static io.openems.edge.timeofusetariff.octopus.dynamic.gridbuy.Data.JSON_PRICES;
import static io.openems.edge.timeofusetariff.octopus.dynamic.gridbuy.Data.JSON_TOKEN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.test.ComponentTest;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class TariffOctopusDynamicGridBuyImplTest {

	@Test
	public void testActivationWithDisabledConfig() throws Exception {
		final var sut = new TariffOctopusDynamicGridBuyImpl();
		new ComponentTest(sut) //
				.activate(MyConfig.create() //
						.setId("timeOfUseTariff0") //
						.setEnabled(false) // disabled: no real HTTP requests during test
						.setEmail("test@example.com") //
						.setPassword("secret") //
						.setAccountNumber("A-12345678") //
						.build()) //
		;
	}

	@Test
	public void testIntegrationSuccessfulFetchAndTokenCaching() throws Exception {
		var tokenCalls = new java.util.concurrent.atomic.AtomicInteger(0);
		var priceCalls = new java.util.concurrent.atomic.AtomicInteger(0);

		var client = new OkHttpClient.Builder() //
				.addInterceptor(chain -> {
					var request = chain.request();
					var body = request.body();
					var requestText = "";
					if (body != null) {
						var buffer = new okio.Buffer();
						body.writeTo(buffer);
						requestText = buffer.readUtf8();
					}

					if (requestText.contains("obtainKrakenToken")) {
						tokenCalls.incrementAndGet();
						return new Response.Builder() //
								.request(request) //
								.protocol(Protocol.HTTP_1_1) //
								.code(200) //
								.message("OK") //
								.body(ResponseBody.create(JSON_TOKEN, MediaType.parse("application/json"))) //
								.build();
					}

					priceCalls.incrementAndGet();
					return new Response.Builder() //
							.request(request) //
							.protocol(Protocol.HTTP_1_1) //
							.code(200) //
							.message("OK") //
							.body(ResponseBody.create(JSON_PRICES, MediaType.parse("application/json"))) //
							.build();
				}) //
				.build();

		var sut = new TariffOctopusDynamicGridBuyImpl(client);
		new ComponentTest(sut) //
				.activate(MyConfig.create() //
						.setId("timeOfUseTariff0") //
						.setEnabled(false) //
						.setEmail("test@example.com") //
						.setPassword("secret") //
						.setAccountNumber("A-12345678") //
						.build()) //
		;

		sut.task.run();
		sut.channels().forEach(Channel::nextProcessImage);
		assertEquals(200,
				(int) sut.channel(TariffOctopusDynamicGridBuy.ChannelId.HTTP_STATUS_CODE).value().get());
		assertEquals(1, tokenCalls.get());
		assertEquals(1, priceCalls.get());

		sut.task.run();
		assertEquals(1, tokenCalls.get());
		assertEquals(2, priceCalls.get());
	}

	@Test
	public void testIntegrationAuthenticationFailureStatus() throws Exception {
		var client = new OkHttpClient.Builder() //
				.addInterceptor(chain -> new Response.Builder() //
						.request(chain.request()) //
						.protocol(Protocol.HTTP_1_1) //
						.code(400) //
						.message("Bad Request") //
						.body(ResponseBody.create("{}", MediaType.parse("application/json"))) //
						.build()) //
				.build();

		var sut = new TariffOctopusDynamicGridBuyImpl(client);
		new ComponentTest(sut) //
				.activate(MyConfig.create() //
						.setId("timeOfUseTariff0") //
						.setEnabled(false) //
						.setEmail("wrong@example.com") //
						.setPassword("wrong") //
						.setAccountNumber("A-12345678") //
						.build()) //
		;

		sut.task.run();
		sut.channels().forEach(Channel::nextProcessImage);

		assertEquals(400,
				(int) sut.channel(TariffOctopusDynamicGridBuy.ChannelId.HTTP_STATUS_CODE).value().get());
		assertTrue((boolean) sut.channel(TariffOctopusDynamicGridBuy.ChannelId.STATUS_AUTHENTICATION_FAILED).value()
				.get());
	}
}



