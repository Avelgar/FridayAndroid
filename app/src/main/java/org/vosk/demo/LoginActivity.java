package org.vosk.demo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class LoginActivity extends Activity {

    private static final String TAG = "LoginActivity";
    private static final String SETTINGS_FILE_NAME = "settings.json";

    private LinearLayout layoutLoginForm;
    private LinearLayout layoutRegisterForm;
    private Button tabLogin;
    private Button tabRegister;

    // Login views
    private EditText etLoginUser;
    private EditText etLoginPassword;
    private Button btnSubmitLogin;
    private TextView tvForgotPassword;

    // Register views
    private EditText etRegEmail;
    private EditText etRegLogin;
    private EditText etRegPassword;
    private EditText etRegConfirmPassword;
    private Button btnSubmitRegister;

    // Common feedback
    private TextView tvAuthError;
    private ProgressBar pbAuthLoading;
    private LinearLayout layoutSplashLoading;

    private boolean isLoginTab = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        initViews();
        setupListeners();

        // Проверяем сохраненный токен при запуске
        checkExistingAuth();
    }

    private void initViews() {
        layoutLoginForm = findViewById(R.id.layout_login_form);
        layoutRegisterForm = findViewById(R.id.layout_register_form);
        tabLogin = findViewById(R.id.tab_login);
        tabRegister = findViewById(R.id.tab_register);

        etLoginUser = findViewById(R.id.et_login_user);
        etLoginPassword = findViewById(R.id.et_login_password);
        btnSubmitLogin = findViewById(R.id.btn_submit_login);
        tvForgotPassword = findViewById(R.id.tv_forgot_password);

        etRegEmail = findViewById(R.id.et_reg_email);
        etRegLogin = findViewById(R.id.et_reg_login);
        etRegPassword = findViewById(R.id.et_reg_password);
        etRegConfirmPassword = findViewById(R.id.et_reg_confirm_password);
        btnSubmitRegister = findViewById(R.id.btn_submit_register);

        tvAuthError = findViewById(R.id.tv_auth_error);
        pbAuthLoading = findViewById(R.id.pb_auth_loading);
        layoutSplashLoading = findViewById(R.id.layout_splash_loading);
    }

    private void setupListeners() {
        tabLogin.setOnClickListener(v -> selectTab(true));
        tabRegister.setOnClickListener(v -> selectTab(false));

        tvForgotPassword.setOnClickListener(v -> {
            Intent intent = new Intent(LoginActivity.this, RecoveryPasswordActivity.class);
            startActivity(intent);
        });

        btnSubmitLogin.setOnClickListener(v -> performLogin());
        btnSubmitRegister.setOnClickListener(v -> performRegister());
    }

    private void selectTab(boolean login) {
        isLoginTab = login;
        clearError();

        if (login) {
            tabLogin.setBackgroundResource(R.drawable.button_background);
            tabLogin.setTextColor(ContextCompat.getColor(this, R.color.white));

            tabRegister.setBackgroundColor(Color.TRANSPARENT);
            tabRegister.setTextColor(ContextCompat.getColor(this, R.color.graphite_soft));

            layoutLoginForm.setVisibility(View.VISIBLE);
            layoutRegisterForm.setVisibility(View.GONE);
        } else {
            tabRegister.setBackgroundResource(R.drawable.button_background);
            tabRegister.setTextColor(ContextCompat.getColor(this, R.color.white));

            tabLogin.setBackgroundColor(Color.TRANSPARENT);
            tabLogin.setTextColor(ContextCompat.getColor(this, R.color.graphite_soft));

            layoutLoginForm.setVisibility(View.GONE);
            layoutRegisterForm.setVisibility(View.VISIBLE);
        }
    }

    private void checkExistingAuth() {
        String token = getSavedToken();
        if (token == null || token.trim().isEmpty()) {
            return;
        }

        // Показываем сплеш-загрузку на время валидации токена
        if (layoutSplashLoading != null) {
            layoutSplashLoading.setVisibility(View.VISIBLE);
        }

        new Thread(() -> {
            try {
                OkHttpClient httpClient = new OkHttpClient();
                Request request = new Request.Builder()
                        .url("https://friday-assistant.ru/verify_token?token=" + token)
                        .get()
                        .build();

                Response response = httpClient.newCall(request).execute();
                String respStr = response.body().string();
                JSONObject json = new JSONObject(respStr);

                if ("success".equals(json.optString("status"))) {
                    String userLogin = json.optString("user_login");
                    saveAuthData(token, userLogin);

                    runOnUiThread(() -> {
                        openMainActivity();
                    });
                } else {
                    // Токен устарел или отозван
                    saveAuthData(null, null);
                    runOnUiThread(() -> {
                        if (layoutSplashLoading != null) layoutSplashLoading.setVisibility(View.GONE);
                        showError("Сессия истекла. Пожалуйста, выполните вход заново.");
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error verifying existing token", e);
                // При ошибке соединения даем пользователю войти или повторить
                runOnUiThread(() -> {
                    if (layoutSplashLoading != null) layoutSplashLoading.setVisibility(View.GONE);
                });
            }
        }).start();
    }

    private void performLogin() {
        String loginOrEmail = etLoginUser.getText().toString().trim();
        String password = etLoginPassword.getText().toString();

        if (TextUtils.isEmpty(loginOrEmail) || TextUtils.isEmpty(password)) {
            showError("Все поля обязательны для заполнения");
            return;
        }

        setLoading(true);

        new Thread(() -> {
            try {
                OkHttpClient httpClient = new OkHttpClient();
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");

                JSONObject json = new JSONObject();
                json.put("login", loginOrEmail);
                json.put("password", password);

                RequestBody body = RequestBody.create(JSON, json.toString());
                Request request = new Request.Builder()
                        .url("https://friday-assistant.ru/login")
                        .post(body)
                        .build();

                Response response = httpClient.newCall(request).execute();
                String responseBody = response.body().string();

                runOnUiThread(() -> {
                    setLoading(false);
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if ("success".equals(jsonResponse.optString("status"))) {
                            String userLogin = jsonResponse.optString("user_login");
                            String token = jsonResponse.optString("token");

                            saveAuthData(token, userLogin);
                            Toast.makeText(LoginActivity.this, "Вход выполнен успешно!", Toast.LENGTH_SHORT).show();
                            openMainActivity();
                        } else {
                            showError(jsonResponse.optString("message", "Неверный логин или пароль"));
                        }
                    } catch (JSONException e) {
                        showError("Ошибка обработки ответа сервера");
                        Log.e(TAG, "JSON parse error", e);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Login request error", e);
                runOnUiThread(() -> {
                    setLoading(false);
                    showError("Ошибка соединения: " + e.getMessage());
                });
            }
        }).start();
    }

    private void performRegister() {
        String email = etRegEmail.getText().toString().trim();
        String login = etRegLogin.getText().toString().trim();
        String password = etRegPassword.getText().toString();
        String confirmPassword = etRegConfirmPassword.getText().toString();

        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(login) ||
                TextUtils.isEmpty(password) || TextUtils.isEmpty(confirmPassword)) {
            showError("Все поля обязательны для заполнения");
            return;
        }

        if (!email.contains("@") || !email.contains(".")) {
            showError("Введите корректный email адрес");
            return;
        }

        if (password.length() < 6) {
            showError("Пароль должен содержать минимум 6 символов");
            return;
        }

        if (!password.equals(confirmPassword)) {
            showError("Пароли не совпадают");
            return;
        }

        setLoading(true);

        new Thread(() -> {
            try {
                OkHttpClient httpClient = new OkHttpClient();
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");

                JSONObject json = new JSONObject();
                json.put("email", email);
                json.put("login", login);
                json.put("password", password);

                RequestBody body = RequestBody.create(JSON, json.toString());
                Request request = new Request.Builder()
                        .url("https://friday-assistant.ru/register")
                        .post(body)
                        .build();

                Response response = httpClient.newCall(request).execute();
                String responseBody = response.body().string();

                runOnUiThread(() -> {
                    setLoading(false);
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if (response.code() == 201 || "success".equals(jsonResponse.optString("status"))) {
                            new AlertDialog.Builder(LoginActivity.this)
                                    .setTitle("Регистрация успешна")
                                    .setMessage("На вашу почту " + email + " отправлено письмо с ссылкой для подтверждения. Пожалуйста, подтвердите email перед входом.")
                                    .setPositiveButton("Войти", (d, w) -> {
                                        selectTab(true);
                                        etLoginUser.setText(login);
                                        etLoginPassword.requestFocus();
                                    })
                                    .setCancelable(false)
                                    .show();
                        } else {
                            showError(jsonResponse.optString("message", "Ошибка регистрации"));
                        }
                    } catch (JSONException e) {
                        showError("Ошибка обработки ответа сервера");
                        Log.e(TAG, "JSON parse error", e);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Register request error", e);
                runOnUiThread(() -> {
                    setLoading(false);
                    showError("Ошибка при отправке запроса: " + e.getMessage());
                });
            }
        }).start();
    }

    private void openMainActivity() {
        Intent intent = new Intent(LoginActivity.this, FridayActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void setLoading(boolean loading) {
        if (pbAuthLoading != null) {
            pbAuthLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
        if (btnSubmitLogin != null) btnSubmitLogin.setEnabled(!loading);
        if (btnSubmitRegister != null) btnSubmitRegister.setEnabled(!loading);
        if (loading) clearError();
    }

    private void showError(String message) {
        if (tvAuthError != null) {
            tvAuthError.setText(message);
            tvAuthError.setVisibility(View.VISIBLE);
        }
    }

    private void clearError() {
        if (tvAuthError != null) {
            tvAuthError.setText("");
            tvAuthError.setVisibility(View.GONE);
        }
    }

    private String getSavedToken() {
        JSONObject settings = readDataFromFile(this, SETTINGS_FILE_NAME);
        if (settings != null && settings.has("user_token")) {
            return settings.optString("user_token");
        }
        return null;
    }

    private void saveAuthData(String token, String userLogin) {
        try {
            JSONObject settings = readDataFromFile(this, SETTINGS_FILE_NAME);
            if (settings == null) settings = new JSONObject();

            if (token != null && !token.isEmpty()) settings.put("user_token", token);
            else settings.remove("user_token");

            if (userLogin != null && !userLogin.isEmpty()) settings.put("user_login", userLogin);
            else settings.remove("user_login");

            saveDataToFile(this, SETTINGS_FILE_NAME, settings);
        } catch (JSONException e) {
            Log.e(TAG, "Error saving auth data", e);
        }
    }

    private void saveDataToFile(Context context, String fileName, JSONObject jsonData) {
        try (FileOutputStream fos = context.openFileOutput(fileName, Context.MODE_PRIVATE)) {
            fos.write(jsonData.toString().getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private JSONObject readDataFromFile(Context context, String fileName) {
        StringBuilder jsonString = new StringBuilder();
        try (FileInputStream fis = context.openFileInput(fileName);
             InputStreamReader isr = new InputStreamReader(fis);
             BufferedReader bufferedReader = new BufferedReader(isr)) {
            String line;
            while ((line = bufferedReader.readLine()) != null) jsonString.append(line);
            return new JSONObject(jsonString.toString());
        } catch (IOException | JSONException e) {
            return null;
        }
    }
}
