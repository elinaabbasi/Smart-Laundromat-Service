package com.smartlaundromat;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;

@Component
class StripeTestPayments {
    private final String secretKey;
    private final RestClient stripe;

    StripeTestPayments(RestClient.Builder builder, @Value("${laundromat.stripe.test-secret-key}") String secretKey) {
        this.secretKey = secretKey.strip();
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(Duration.ofSeconds(8));
        stripe = builder
                .requestFactory(requests)
                .baseUrl("https://api.stripe.com")
                .defaultHeader("Stripe-Version", "2025-02-24.acacia")
                .build();
    }

    static void validateMethod(String method) {
        if (!"pm_card_visa".equals(method) && !"pm_card_visa_chargeDeclined".equals(method)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a Stripe test payment method.");
        }
    }

    String createCustomer(String name, String email, String idempotencyKey) {
        var fields = new LinkedMultiValueMap<String, String>();
        fields.add("name", name);
        fields.add("email", email);

        try {
            JsonNode customer = postForm("/v1/customers", idempotencyKey, fields)
                    .retrieve()
                    .body(JsonNode.class);
            if (isTestObject(customer) && customer.path("id").asText().startsWith("cus_")) {
                return customer.path("id").asText();
            }
        } catch (RestClientException error) {
            throw stripeUnavailable();
        }
        throw stripeUnavailable();
    }

    Result pay(String method, int amountCents, String currency, String idempotencyKey, String customerId) {
        String stripeCurrency = currency.toLowerCase(Locale.ROOT);
        var fields = new LinkedMultiValueMap<String, String>();
        fields.add("amount", Integer.toString(amountCents));
        fields.add("currency", stripeCurrency);
        fields.add("payment_method", method);
        fields.add("customer", customerId);
        fields.add("payment_method_types[]", "card");
        fields.add("capture_method", "automatic");
        fields.add("confirm", "true");

        try {
            return postForm("/v1/payment_intents", idempotencyKey, fields)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() == 402) {
                            JsonNode error = response.bodyTo(JsonNode.class);
                            if (error != null && "card_error".equals(error.path("error").path("type").asText())) {
                                JsonNode intent = error.path("error").path("payment_intent");
                                String id = intent.isMissingNode() || intent.isNull()
                                        ? null : testIntentId(intent, amountCents, stripeCurrency);
                                return new Result(false, id);
                            }
                        } else if (response.getStatusCode().is2xxSuccessful()) {
                            JsonNode intent = response.bodyTo(JsonNode.class);
                            String id = testIntentId(intent, amountCents, stripeCurrency);
                            if ("succeeded".equals(intent.path("status").asText())
                                    && intent.path("amount_received").asInt() == amountCents) {
                                return new Result(true, id);
                            }
                        }
                        throw stripeUnavailable();
                    });
        } catch (RestClientException error) {
            throw stripeUnavailable();
        }
    }

    void refund(String paymentIntentId, String idempotencyKey) {
        var fields = new LinkedMultiValueMap<String, String>();
        fields.add("payment_intent", paymentIntentId);

        try {
            postForm("/v1/refunds", idempotencyKey, fields).retrieve().toBodilessEntity();
        } catch (RestClientException error) {
            throw stripeUnavailable();
        }
    }

    private RestClient.RequestBodySpec postForm(
            String path, String idempotencyKey, LinkedMultiValueMap<String, String> fields) {
        if (!secretKey.startsWith("sk_test_") && !secretKey.startsWith("rk_test_")) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configure a Stripe sandbox key.");
        }
        return stripe.post()
                .uri(path)
                .headers(headers -> headers.setBearerAuth(secretKey))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(fields);
    }

    private static boolean isTestObject(JsonNode object) {
        return object != null && object.path("livemode").isBoolean() && !object.path("livemode").asBoolean();
    }

    private static String testIntentId(JsonNode intent, int amountCents, String currency) {
        if (isTestObject(intent)
                && intent.path("amount").asInt() == amountCents
                && currency.equals(intent.path("currency").asText())) {
            String id = intent.path("id").asText();
            if (id.startsWith("pi_")) return id;
        }
        throw stripeUnavailable();
    }

    private static ResponseStatusException stripeUnavailable() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not complete the Stripe test payment.");
    }

    record Result(boolean paid, String intentId) {}
}
