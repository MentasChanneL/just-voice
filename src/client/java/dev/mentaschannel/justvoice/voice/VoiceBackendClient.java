package dev.mentaschannel.justvoice.voice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Exchanges a one-time invitation ticket for player-scoped Mumble credentials.
 * The world API key and Murmur service password are never sent to this client.
 */
public final class VoiceBackendClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final URI redeemUri;
    private final HttpClient httpClient;

    public VoiceBackendClient(URI backendUri) {
        if (backendUri == null) {
            throw new IllegalArgumentException("Backend URL is not configured");
        }

        this.redeemUri = backendUri.resolve("/v1/invites/redeem");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public CompletableFuture<VoiceCredentials> redeemInvite(String ticket, String playerName) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("ticket", ticket);
        requestBody.addProperty("player_name", playerName);

        HttpRequest request = HttpRequest.newBuilder(redeemUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::parseResponse);
    }

    private VoiceCredentials parseResponse(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Voice invitation was rejected (HTTP " + response.statusCode() + ")."
            );
        }

        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            return new VoiceCredentials(
                    json.get("mumble_host").getAsString(),
                    json.get("mumble_port").getAsInt(),
                    json.get("username").getAsString(),
                    json.get("access_token").getAsString()
            );
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Voice backend returned an invalid response.", exception);
        }
    }
}
