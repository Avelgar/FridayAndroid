package org.vosk.demo;

import okhttp3.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class GeminiService {
    private static final String SERVER_URL = "https://friday-assistant.ru/generate";
    private static final OkHttpClient client = new OkHttpClient();

    public static String generateText(String prompt) throws IOException, ExecutionException, InterruptedException {
        // Create the JSON body
        JsonObject jsonBody = new JsonObject();
        jsonBody.addProperty("prompt", prompt);

        RequestBody body = RequestBody.create(jsonBody.toString(), MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(SERVER_URL)
                .post(body)
                .build();

        Future<String> futureResponse = Executors.newSingleThreadExecutor().submit(() -> {
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
                return response.body().string();
            }
        });

        String responseJson = futureResponse.get();
        return extractTextFromResponse(responseJson);
    }

    public static String extractTextFromResponse(String response) {
        // Parse the JSON response
        JsonObject jsonObject = JsonParser.parseString(response).getAsJsonObject();
        if (jsonObject.has("response")) {
            return jsonObject.get("response").getAsString(); // Return the text from the response
        }
        return ""; // Return empty if no response found
    }
}
