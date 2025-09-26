package org.vosk.demo;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class RecoveryPasswordActivity extends Activity {

    private EditText etEmail;
    private Button btnRecover;
    private TextView tvResponse;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recovery_password);

        etEmail = findViewById(R.id.etEmail);
        btnRecover = findViewById(R.id.btnRecover);
        tvResponse = findViewById(R.id.tvResponse);
        progressBar = findViewById(R.id.progressBar);

        btnRecover.setOnClickListener(v -> {
            String email = etEmail.getText().toString().trim();
            if (email.isEmpty() || !email.contains("@")) {
                tvResponse.setText("Пожалуйста, введите корректный email");
                tvResponse.setVisibility(View.VISIBLE);
                return;
            }
            recoverPassword(email);
        });
    }

    private void recoverPassword(String email) {
        progressBar.setVisibility(View.VISIBLE);
        btnRecover.setEnabled(false);
        tvResponse.setVisibility(View.GONE);

        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");

                JSONObject json = new JSONObject();
                json.put("email", email);

                RequestBody body = RequestBody.create(JSON, json.toString());
                Request request = new Request.Builder()
                        .url("https://friday-assistant.ru/recover-password")
                        .post(body)
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body().string();

                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnRecover.setEnabled(true);

                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if (jsonResponse.getString("status").equals("success")) {
                            tvResponse.setTextColor(getResources().getColor(android.R.color.holo_green_light));
                            tvResponse.setText("Инструкции по восстановлению отправлены на ваш email");
                        } else {
                            tvResponse.setTextColor(getResources().getColor(android.R.color.holo_red_light));
                            tvResponse.setText("Ошибка: " + jsonResponse.getString("message"));
                        }
                    } catch (Exception e) {
                        tvResponse.setTextColor(getResources().getColor(android.R.color.holo_red_light));
                        tvResponse.setText("Ошибка обработки ответа сервера");
                    }
                    tvResponse.setVisibility(View.VISIBLE);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnRecover.setEnabled(true);
                    tvResponse.setTextColor(getResources().getColor(android.R.color.holo_red_light));
                    tvResponse.setText("Ошибка подключения: " + e.getMessage());
                    tvResponse.setVisibility(View.VISIBLE);
                });
                e.printStackTrace();
            }
        }).start();
    }
}