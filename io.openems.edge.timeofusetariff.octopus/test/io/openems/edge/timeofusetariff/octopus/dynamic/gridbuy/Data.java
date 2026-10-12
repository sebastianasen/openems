package io.openems.edge.timeofusetariff.octopus.dynamic.gridbuy;

/**
 * Sample Octopus Kraken API response used in unit tests.
 *
 * <p>
 * The response contains a subset of real data for 2026-05-26 with 15-minute
 * resolution. Prices are in cents/kWh (gross).
 */
public final class Data {

	protected static final String JSON_PRICES = """
			{
			  "data": {
			    "account": {
			      "properties": [
			        {
			          "electricityMalos": [
			            {
			              "agreements": [
			                {
			                  "unitRateForecast": []
			                },
			                {
			                  "unitRateForecast": [
			                    {
			                      "validFrom": "2026-05-26T00:00:00+02:00",
			                      "validTo": "2026-05-25T22:15:00+00:00",
			                      "unitRateInformation": {
			                        "rates": [
			                          {
			                            "latestGrossUnitRateCentsPerKwh": "33.12841"
			                          }
			                        ]
			                      }
			                    },
			                    {
			                      "validFrom": "2026-05-25T22:15:00+00:00",
			                      "validTo": "2026-05-25T22:30:00+00:00",
			                      "unitRateInformation": {
			                        "rates": [
			                          {
			                            "latestGrossUnitRateCentsPerKwh": "31.59569"
			                          }
			                        ]
			                      }
			                    },
			                    {
			                      "validFrom": "2026-05-25T22:30:00+00:00",
			                      "validTo": "2026-05-25T22:45:00+00:00",
			                      "unitRateInformation": {
			                        "rates": [
			                          {
			                            "latestGrossUnitRateCentsPerKwh": "30.42473"
			                          }
			                        ]
			                      }
			                    },
			                    {
			                      "validFrom": "2026-05-25T22:45:00+00:00",
			                      "validTo": "2026-05-25T23:00:00+00:00",
			                      "unitRateInformation": {
			                        "rates": [
			                          {
			                            "latestGrossUnitRateCentsPerKwh": "30.26408"
			                          }
			                        ]
			                      }
			                    }
			                  ]
			                }
			              ]
			            }
			          ]
			        }
			      ]
			    }
			  }
			}""";

	protected static final String JSON_TOKEN = """
			{
			  "data": {
			    "obtainKrakenToken": {
			      "token": "test-token-value"
			    }
			  }
			}""";

	private Data() {
	}
}

