// Copyright 2019 Alpha Cephei Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.vosk.demo;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.SpeechStreamService;
import org.vosk.android.StorageService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.content.Intent;
import android.os.Build;
import android.widget.Toast;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import android.util.Base64;

import java.io.UnsupportedEncodingException;
import okhttp3.MediaType;
import okhttp3.RequestBody;

public class FridayActivity extends Activity implements
        RecognitionListener {

    private static FridayActivity instance;

    static private final int STATE_START = 0;
    static private final int STATE_READY = 1;
    static private final int STATE_DONE = 2;
    static private final int STATE_FILE = 3;
    static private final int STATE_MIC = 4;

    /* Used to handle permission request */
    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;

    private Model model;
    SpeechService speechService;
    private SpeechStreamService speechStreamService;
    static TextView resultView;
    private static TextToSpeech textToSpeech;

    private WebSocket webSocket;

    public boolean isRegistrationWindowOpen = false;

    private LinearLayout mainContent;
    private LinearLayout changeDataContent;
    private ScrollView devicesContent;
    private ScrollView settingsContent;

    String botName;

    private Handler connectionHandler = new Handler();
    private static final long CONNECTION_CHECK_INTERVAL = 30000;
    public OkHttpClient client;
    public boolean isDestroyed = false;

    public String pendingHistory = null;
    private String pendingUserLogin = null;

    private StringBuilder currentHistory = new StringBuilder();

    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private TextureView textureView;
    private Camera camera;
    private File outputFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;

        checkAndCreateSettingsFile();

        String settingsFileName = "settings.json";
        JSONObject settings = readDataFromFile(this, settingsFileName);
        try {
            if (settings != null && settings.has("name")) {
                botName = settings.getString("name").toLowerCase();
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        // Инициализация WebSocket соединения и отправка данных
        initWebSocketConnection();
        startConnectionChecker();
    }

    public static FridayActivity getInstance() {
        return instance;
    }

    public static void setInstance(FridayActivity activity) {
        instance = activity;
    }

    // Добавьте эти методы в ваш класс FridayActivity
    public boolean isDataValidForTest(JSONObject data) {
        return isDataValid(data);
    }

    public void testOnResult(String hypothesis) {
        onResult(hypothesis);
    }

    public void initWebSocketConnection() {
        if (isDestroyed) return; // Не пытаться переподключаться после уничтожения

        if (client == null) {
            client = new OkHttpClient();
        }
        Request request = new Request.Builder().url("ws://blue.fnode.me:8114").build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                if (!isDestroyed) {
                    sendDeviceData();
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                if (!isDestroyed) {
                    try {
                        byte[] decodedBytes = Base64.decode(text, Base64.DEFAULT);
                        String decodedMessage = new String(decodedBytes, "UTF-8");
                        handleIncomingMessage(decodedMessage);
                    } catch (UnsupportedEncodingException e) {
                        Log.e("WebSocket", "Encoding error", e);
                        handleIncomingMessage(text);
                    }
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (!isDestroyed) {
                    Log.e("WebSocket", "Connection failed", t);
                    // Задержка перед повторным подключением
                    connectionHandler.postDelayed(() -> initWebSocketConnection(), 5000);
                }
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                Log.i("WebSocket", "Connection closed: " + reason);
            }
        });
    }

    private void sendDeviceData() {
        String fileName = "device_data.json";
        JSONObject deviceData = readDataFromFile(this, fileName);

        if (deviceData == null || !isDataValid(deviceData)) {
            runOnUiThread(this::openRegistrationWindow);
        } else {
            try {
                String deviceName = deviceData.getString("deviceName");
                String password = deviceData.getString("password");
                String macAddress = getUniqueDeviceId(this);

                JSONObject deviceInfo = new JSONObject();
                deviceInfo.put("MAC", macAddress);
                deviceInfo.put("DeviceName", deviceName);
                deviceInfo.put("Password", password);

                sendEncodedMessage(deviceInfo);
            } catch (JSONException e) {
                Log.e("Registration", "Error preparing device data", e);
                runOnUiThread(this::openRegistrationWindow);
            }
        }
    }

    private void startConnectionChecker() {
        connectionHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkWebSocketConnection();
                connectionHandler.postDelayed(this, CONNECTION_CHECK_INTERVAL);
            }
        }, CONNECTION_CHECK_INTERVAL);
    }

    private void checkWebSocketConnection() {
        if (webSocket == null) {
            // Если соединение отсутствует, инициализируем заново
            initWebSocketConnection();
        } else {
            // Если соединение активно, отправляем ping
            sendPing();
        }
    }

    private void sendPing() {
        try {
            JSONObject pingMessage = new JSONObject();
            pingMessage.put("type", "ping");
            pingMessage.put("mac", getUniqueDeviceId(this));
            sendEncodedMessage(pingMessage);
        } catch (JSONException e) {
            Log.e("WebSocket", "Error creating ping message", e);
        }
    }

    private void sendEncodedMessage(JSONObject message) {
        if (webSocket != null) {
            try {
                String jsonString = message.toString();
                byte[] data = jsonString.getBytes("UTF-8");
                String encodedData = Base64.encodeToString(data, Base64.DEFAULT);
                webSocket.send(encodedData);
            } catch (UnsupportedEncodingException e) {
                Log.e("WebSocket", "Encoding error", e);
                // В случае ошибки пробуем отправить без кодирования
                webSocket.send(message.toString());
            }
        } else {
            Log.e("WebSocket", "WebSocket connection is not established");
        }
    }


    private void openRegistrationWindow() {
        isRegistrationWindowOpen = true;
        setContentView(R.layout.registration);

        EditText deviceNameEditText = findViewById(R.id.deviceName);
        EditText passwordEditText = findViewById(R.id.password);
        Button registrationButton = findViewById(R.id.registration);

        registrationButton.setOnClickListener(view -> {
            try {
                String deviceName = deviceNameEditText.getText().toString();
                String password = passwordEditText.getText().toString();
                String macAddress = getUniqueDeviceId(this);

                if (deviceName.isEmpty() || password.isEmpty()) {
                    Toast.makeText(this, "Пожалуйста, заполните все поля.", Toast.LENGTH_SHORT).show();
                    return;
                }

                JSONObject deviceInfo = new JSONObject();
                deviceInfo.put("MAC", macAddress);
                deviceInfo.put("DeviceName", deviceName);
                deviceInfo.put("Password", password);

                String jsonString = deviceInfo.toString();
                byte[] data = jsonString.getBytes("UTF-8");
                String encodedData = Base64.encodeToString(data, Base64.DEFAULT);
                webSocket.send(encodedData);
            } catch (Exception e) {
                Log.e("Registration", "Error preparing device data", e);
            }
        });
    }

    public void handleIncomingMessage(String encodedMessage) {
        runOnUiThread(() -> {
            try {
                JSONObject jsonMessage = new JSONObject(encodedMessage);

                if (jsonMessage.has("type") && jsonMessage.getString("type").equals("command_execution")) {
                    // Обработка команды выполнения

                    try {
                        JSONObject backgroundCommand = new JSONObject(jsonMessage.toString());
                        executeInBackground(backgroundCommand);

                        return;
                    }
                    catch (JSONException e) {
                        Log.e("CommandHandler", "Error processing command", e);
                        Toast.makeText(this, "Ошибка выполнения команды", Toast.LENGTH_SHORT).show();
                    }
                }
                else if (jsonMessage.has("type") && jsonMessage.getString("type").equals("data_request")) {
                    // Обработка запроса данных
                    try {
                        boolean needProcesses = jsonMessage.optBoolean("need_processes", false);
                        boolean needPrograms = jsonMessage.optBoolean("need_programs", false);
                        JSONArray programs = new JSONArray();
                        JSONArray runningApps = new JSONArray();
                        if (needProcesses) {
                            runningApps = getRunningAppsWithTitles();
                        }
                        if (needPrograms) {
                            programs = getInstalledPackages();
                        }
                        // Формируем ответ
                        JSONObject response = new JSONObject();
                        response.put("command_to_device", jsonMessage.optString("original_command", ""));
                        response.put("processes", runningApps.toString());
                        response.put("name", jsonMessage.optString("name", ""));
                        response.put("source_name", jsonMessage.optString("source_device"));
                        response.put("programs", programs);

                        // Отправляем ответ
                        sendEncodedMessage(response);

                        Log.d("DataRequest", "Отправлены пользовательские приложения: " + runningApps.toString());

                    } catch (JSONException e) {
                        Log.e("DataRequest", "Error processing data request", e);
                    }
                    return; // Прерываем дальнейшую обработку
                }

                // Остальная обработка сообщений
                if (encodedMessage.contains("Имя устройства уже занято.")) {
                    Toast.makeText(this, "Это имя устройства уже занято. Пожалуйста, выберите другое.",
                            Toast.LENGTH_LONG).show();
                    if (!isRegistrationWindowOpen) {
                        openRegistrationWindow();
                    }
                }
                else if (encodedMessage.contains("Данные успешно обработаны")) {
                    try {
                        JSONObject jsonData = new JSONObject(encodedMessage);

                        if (isRegistrationWindowOpen) {
                            EditText deviceNameEditText = findViewById(R.id.deviceName);
                            EditText passwordEditText = findViewById(R.id.password);
                            String deviceName = deviceNameEditText.getText().toString();
                            String password = passwordEditText.getText().toString();

                            JSONObject deviceData = new JSONObject();
                            try {
                                deviceData.put("deviceName", deviceName);
                                deviceData.put("password", password);
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }

                            saveDataToFile(this, "device_data.json", deviceData);
                            Toast.makeText(this, "Данные успешно сохранены", Toast.LENGTH_LONG).show();
                        }

                        // Сохраняем историю и логин для последующего использования
                        if (jsonData.has("history")) {
                            pendingHistory = jsonData.getString("history");
                        }
                        if (jsonData.has("user_login")) {
                            pendingUserLogin = jsonData.getString("user_login");
                        }

                        openMainWindow();

                    } catch (JSONException e) {
                        Log.e("Message", "Error parsing message", e);
                        runOnUiThread(() ->
                                Toast.makeText(this, "Ошибка обработки данных", Toast.LENGTH_SHORT).show());
                    }
                }
                else {
                    Toast.makeText(this, encodedMessage, Toast.LENGTH_LONG).show();
                }
            } catch (JSONException e) {
                Log.e("MessageHandler", "Error parsing message", e);
                Toast.makeText(this, encodedMessage, Toast.LENGTH_LONG).show();
            }
        });
    }
    private void openMainWindow() {
        isRegistrationWindowOpen = false;
        setContentView(R.layout.main);

        // Инициализация всех представлений
        mainContent = findViewById(R.id.main_content);
        changeDataContent = findViewById(R.id.change_data_content);
        devicesContent = findViewById(R.id.devices_content);
        settingsContent = findViewById(R.id.settings_content);

        // Настройка кнопок навигации
        findViewById(R.id.main_btn).setOnClickListener(v -> showMainContent());
        findViewById(R.id.data_window_btn).setOnClickListener(v -> showChangeDataContent());
        findViewById(R.id.devices_btn).setOnClickListener(v -> showDevicesContent());
        findViewById(R.id.settings_btn).setOnClickListener(v -> showSettingsContent());

        // Инициализация новых кнопок
        Button btnRegister = findViewById(R.id.btn_register);
        Button btnLogin = findViewById(R.id.btn_login);
        Button btnUserProfile = findViewById(R.id.btn_user_profile);

        // Настройка видимости кнопок в зависимости от наличия логина
        if (pendingUserLogin != null && !pendingUserLogin.isEmpty()) {
            // Показываем кнопку с логином пользователя
            btnUserProfile.setText(pendingUserLogin);
            btnUserProfile.setVisibility(View.VISIBLE);
            btnRegister.setVisibility(View.GONE);
            btnLogin.setVisibility(View.GONE);

            // Обработчик нажатия на кнопку профиля
            btnUserProfile.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("Выход из аккаунта")
                        .setMessage("Вы уверены, что хотите выйти из аккаунта " + pendingUserLogin + "?")
                        .setPositiveButton("Выйти", (dialog, which) -> logoutUser())
                        .setNegativeButton("Отмена", null)
                        .show();
            });
        } else {
            // Показываем кнопки регистрации и входа
            btnRegister.setVisibility(View.VISIBLE);
            btnLogin.setVisibility(View.VISIBLE);
            btnUserProfile.setVisibility(View.GONE);

            // Обработчики нажатий
            btnRegister.setOnClickListener(v -> {
                showRegistrationDialog();
            });

            btnLogin.setOnClickListener(v -> {
                showLoginDialog();
            });

            if (!isMyServiceRunning(MyForegroundService.class)) {
                Intent serviceIntent = new Intent(this, MyForegroundService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            }
        }

        // Показать основной контент по умолчанию
        showMainContent();

        Intent serviceIntent = new Intent(this, MyForegroundService.class);
        startService(serviceIntent);

        resultView = findViewById(R.id.result_text);
        resultView.setMovementMethod(new ScrollingMovementMethod());
        setUiState(STATE_START);

        // Обработчики кнопок
        findViewById(R.id.recognize_mic).setOnClickListener(view -> recognizeMicrophone());
        findViewById(R.id.clear_history).setOnClickListener(view -> clearHistory());

        LibVosk.setLogLevel(LogLevel.INFO);

        int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(),
                Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSIONS_REQUEST_RECORD_AUDIO);
        } else {
            initModel();
        }

        // Отображаем историю, если она есть
        if (pendingHistory != null) {
            displayHistory(pendingHistory);
            pendingHistory = null; // Очищаем после отображения
        }
    }

    private void displayHistory(String history) {
        if (resultView == null || history == null || history.trim().isEmpty()) {
            return;
        }

        try {
            StringBuilder formattedHistory = new StringBuilder();
            String[] lines = history.split("\n");

            for (String line : lines) {
                if (line.trim().isEmpty()) continue;

                // Определяем тип сообщения по префиксу
                if (line.startsWith("Вы (")) {
                    int start = line.indexOf("): ") + 3;
                    if (start >= 3) {
                        String command = line.substring(start);
                        formattedHistory.append("Вы: ").append(command).append("\n");
                    }
                }
                else if (line.startsWith("Бот (")) {
                    int start = line.indexOf("): ") + 3;
                    if (start >= 3) {
                        String response = line.substring(start);

                        // Извлекаем только голосовые ответы
                        if (response.contains("голосовой ответ|")) {
                            // Находим начало голосового ответа
                            int voiceStart = response.indexOf("голосовой ответ|") + 16;
                            if (voiceStart >= 16) {
                                // Находим конец ответа (до разделителя или конца строки)
                                int voiceEnd = response.indexOf("⸵", voiceStart);
                                if (voiceEnd == -1) voiceEnd = response.length();

                                String voiceResponse = response.substring(voiceStart, voiceEnd);
                                formattedHistory.append("Бот: ").append(voiceResponse).append("\n");
                            }
                        }
                        // Обработка нового формата (чистый ответ)
                        else {
                            formattedHistory.append("Бот: ").append(response).append("\n");
                        }
                    }
                }
                // Для сообщений с других устройств
                else {
                    int prefixEnd = line.indexOf(" (");
                    if (prefixEnd > 0) {
                        String prefix = line.substring(0, prefixEnd);
                        int textStart = line.indexOf("): ") + 3;
                        if (textStart >= 3) {
                            String text = line.substring(textStart);
                            formattedHistory.append(prefix).append(": ").append(text).append("\n");
                        }
                    }
                }
            }

            // Обновляем TextView
            resultView.setText(formattedHistory.toString());
            scrollToBottom();
        } catch (Exception e) {
            Log.e("History", "Error displaying history", e);
        }
    }
    private void showMainContent() {
        mainContent.setVisibility(View.VISIBLE);
        changeDataContent.setVisibility(View.GONE);
        devicesContent.setVisibility(View.GONE);
        settingsContent.setVisibility(View.GONE);
    }

    private boolean isMyServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
    private void showDevicesContent() {
        mainContent.setVisibility(View.GONE);
        changeDataContent.setVisibility(View.GONE);
        devicesContent.setVisibility(View.VISIBLE);
        settingsContent.setVisibility(View.GONE);

        // Находим кнопку подключения устройства и контейнеры
        Button connectDeviceBtn = findViewById(R.id.btn_connect_device);
        LinearLayout accountDevicesContainer = findViewById(R.id.account_devices_container);
        LinearLayout connectedDevicesContainer = findViewById(R.id.connected_devices_container);

        // Очищаем контейнеры перед загрузкой новых данных
        accountDevicesContainer.removeAllViews();
        connectedDevicesContainer.removeAllViews();

        // Устанавливаем обработчик для кнопки подключения устройства
        connectDeviceBtn.setOnClickListener(v -> showConnectDeviceDialog());

        // Получаем MAC-адрес
        String macAddress = getUniqueDeviceId(this);

        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");

                JSONObject json = new JSONObject();
                json.put("mac", macAddress);

                RequestBody body = RequestBody.create(JSON, json.toString());
                Request request = new Request.Builder()
                        .url("http://blue.fnode.me:25550/get_devices")
                        .post(body)
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body().string();

                runOnUiThread(() -> {
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);

                        if (jsonResponse.getString("status").equals("success")) {
                            // Обработка устройств аккаунта
                            JSONArray accountDevices = jsonResponse.getJSONArray("account_devices");
                            for (int i = 0; i < accountDevices.length(); i++) {
                                JSONObject device = accountDevices.getJSONObject(i);
                                addDeviceView(accountDevicesContainer, device, true);
                            }

                            // Обработка подключенных устройств
                            JSONArray myDevices = jsonResponse.getJSONArray("my_devices");
                            for (int i = 0; i < myDevices.length(); i++) {
                                JSONObject device = myDevices.getJSONObject(i);
                                addDeviceView(connectedDevicesContainer, device, false);
                            }
                        } else {
                            Toast.makeText(this, "Ошибка: " + jsonResponse.optString("message", "Неизвестная ошибка"),
                                    Toast.LENGTH_SHORT).show();
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                        Toast.makeText(this, "Ошибка обработки ответа сервера", Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Ошибка при загрузке устройств", Toast.LENGTH_SHORT).show();
                });
                e.printStackTrace();
            }
        }).start();
    }

    private void addDeviceView(LinearLayout container, JSONObject device, boolean isAccountDevice) {
        try {
            // Создаем основной контейнер для устройства
            LinearLayout deviceLayout = new LinearLayout(this);
            deviceLayout.setOrientation(LinearLayout.HORIZONTAL);
            deviceLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            deviceLayout.setPadding(16, 16, 16, 16);
            deviceLayout.setBackgroundResource(R.drawable.device_item_background);

            // Получаем данные с проверкой на null
            String deviceName = device.optString("DeviceName", "Неизвестное устройство");
            String macAddress = device.optString("MacAddress", "MAC не указан");
            boolean isOnline = device.optBoolean("IsOnline", false);

            // Устанавливаем вес для правильного распределения пространства
            float textWeight = isAccountDevice ? 1.0f : 0.7f;

            // Контейнер для текстовой информации
            LinearLayout textContainer = new LinearLayout(this);
            textContainer.setOrientation(LinearLayout.VERTICAL);
            textContainer.setLayoutParams(new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    textWeight));
            textContainer.setPadding(0, 0, 16, 0);

            // Настройки для текстовых полей
            int textColor = Color.parseColor("#333333");
            int macTextColor = Color.parseColor("#666666");
            int textSize = 16;
            int macTextSize = 14;

            // Название устройства
            TextView nameView = new TextView(this);
            nameView.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            nameView.setText(deviceName);
            nameView.setTextColor(textColor);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize);
            nameView.setTypeface(null, Typeface.BOLD);
            nameView.setSingleLine(false);
            nameView.setMaxLines(2);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            textContainer.addView(nameView);

            // MAC-адрес и статус
            LinearLayout statusContainer = new LinearLayout(this);
            statusContainer.setOrientation(LinearLayout.HORIZONTAL);
            statusContainer.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            // MAC-адрес
            TextView macView = new TextView(this);
            macView.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            macView.setText(macAddress);
            macView.setTextColor(macTextColor);
            macView.setTextSize(TypedValue.COMPLEX_UNIT_SP, macTextSize);
            macView.setSingleLine(true);
            macView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            statusContainer.addView(macView);

            // Разделитель
            TextView separator = new TextView(this);
            separator.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            separator.setText(" - ");
            separator.setTextColor(macTextColor);
            separator.setTextSize(TypedValue.COMPLEX_UNIT_SP, macTextSize);
            statusContainer.addView(separator);

            // Статус
            TextView statusView = new TextView(this);
            statusView.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            statusView.setText(isOnline ? "онлайн" : "офлайн");
            statusView.setTextColor(isOnline ? Color.GREEN : Color.RED);
            statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, macTextSize);
            statusContainer.addView(statusView);

            textContainer.addView(statusContainer);
            deviceLayout.addView(textContainer);

            // Для подключенных устройств добавляем кнопку "Отключиться"
            if (!isAccountDevice) {
                Button disconnectBtn = new Button(this);
                LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                btnParams.gravity = Gravity.CENTER_VERTICAL;
                disconnectBtn.setLayoutParams(btnParams);

                disconnectBtn.setText("Отключиться");
                disconnectBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                disconnectBtn.setPadding(16, 8, 16, 8);
                disconnectBtn.setBackgroundResource(R.drawable.disconnect_button_bg);
                disconnectBtn.setTextColor(Color.WHITE);

                disconnectBtn.setOnClickListener(v -> {
                    try {
                        String macToDisconnect = device.optString("MacAddress", "");
                        if (!macToDisconnect.isEmpty()) {
                            Toast.makeText(this, "Отключение устройства " + macToDisconnect,
                                    Toast.LENGTH_SHORT).show();
                            disconnectDevice(macToDisconnect);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(this, "Ошибка при отключении устройства",
                                Toast.LENGTH_SHORT).show();
                    }
                });
                deviceLayout.addView(disconnectBtn);
            }

            container.addView(deviceLayout);
        } catch (Exception e) {
            e.printStackTrace();
            Log.e("DeviceView", "Ошибка при создании view для устройства: " + e.getMessage());
        }
    }

    private void disconnectDevice(String macAddress) {
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");

                JSONObject json = new JSONObject();
                json.put("requester_mac", getUniqueDeviceId(this)); // MAC текущего устройства
                json.put("target_mac", macAddress); // MAC устройства для отключения

                RequestBody body = RequestBody.create(JSON, json.toString());
                Request request = new Request.Builder()
                        .url("http://blue.fnode.me:25550/disconnect_device") // Замените на ваш endpoint
                        .post(body)
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body().string();

                runOnUiThread(() -> {
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if (jsonResponse.getString("status").equals("success")) {
                            Toast.makeText(this, "Устройство отключено", Toast.LENGTH_SHORT).show();
                            // Обновляем список устройств
                            showDevicesContent();
                        } else {
                            Toast.makeText(this, "Ошибка: " + jsonResponse.optString("message", "Не удалось отключить устройство"),
                                    Toast.LENGTH_SHORT).show();
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                        Toast.makeText(this, "Ошибка обработки ответа сервера", Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Ошибка при отключении устройства", Toast.LENGTH_SHORT).show();
                });
                e.printStackTrace();
            }
        }).start();
    }

    private void showConnectDeviceDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_connect_device, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        dialog.show();

        EditText etDeviceName = dialogView.findViewById(R.id.et_device_name);
        EditText etDevicePassword = dialogView.findViewById(R.id.et_device_password);
        Button btnConnect = dialogView.findViewById(R.id.btn_connect);
        ProgressBar progressBar = dialogView.findViewById(R.id.progress_bar);
        TextView tvResponse = dialogView.findViewById(R.id.tv_response);

        btnConnect.setOnClickListener(v -> {
            String deviceName = etDeviceName.getText().toString().trim();
            String password = etDevicePassword.getText().toString().trim();

            if (deviceName.isEmpty() || password.isEmpty()) {
                tvResponse.setText("Заполните все поля");
                tvResponse.setTextColor(Color.RED);
                tvResponse.setVisibility(View.VISIBLE);
                return;
            }

            // Показать прогресс бар и скрыть кнопку
            progressBar.setVisibility(View.VISIBLE);
            btnConnect.setVisibility(View.GONE);
            tvResponse.setVisibility(View.GONE);

            connectToDevice(deviceName, password, new ConnectDeviceCallback() {
                @Override
                public void onComplete(boolean success, String message) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        btnConnect.setVisibility(View.VISIBLE);

                        tvResponse.setText(message);
                        tvResponse.setTextColor(success ? Color.GREEN : Color.RED);
                        tvResponse.setVisibility(View.VISIBLE);

                        if (success) {
                            // Обновить список устройств через 1 секунду
                            new Handler().postDelayed(() -> {
                                showDevicesContent();
                                dialog.dismiss();
                            }, 1000);
                        }
                    });
                }
            });
        });
    }

    public interface ConnectDeviceCallback {
        void onComplete(boolean success, String message);
    }

    private void connectToDevice(String deviceName, String password, ConnectDeviceCallback callback) {
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");

                JSONObject json = new JSONObject();
                json.put("MAC", getUniqueDeviceId(this));
                json.put("DeviceName", deviceName);
                json.put("Password", password);

                RequestBody body = RequestBody.create(JSON, json.toString());
                Request request = new Request.Builder()
                        .url("http://blue.fnode.me:25550/connect_device")
                        .post(body)
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body().string();
                JSONObject jsonResponse = new JSONObject(responseBody);

                if (jsonResponse.getString("status").equals("success")) {
                    callback.onComplete(true, "Устройство успешно подключено!");
                } else {
                    String errorMsg = jsonResponse.optString("message", "Неизвестная ошибка");
                    callback.onComplete(false, "Ошибка: " + errorMsg);
                }
            } catch (Exception e) {
                e.printStackTrace();
                callback.onComplete(false, "Ошибка подключения: " + e.getMessage());
            }
        }).start();
    }

    private void showSettingsContent() {
        mainContent.setVisibility(View.GONE);
        changeDataContent.setVisibility(View.GONE);
        devicesContent.setVisibility(View.GONE);
        settingsContent.setVisibility(View.VISIBLE);

        // Загрузка текущего имени бота из файла
        String settingsFileName = "settings.json";
        JSONObject settings = readDataFromFile(this, settingsFileName);

        EditText botNameInput = findViewById(R.id.bot_name_input);
        Button saveBotNameBtn = findViewById(R.id.save_bot_name_btn);

        try {
            if (settings != null && settings.has("name")) {
                botNameInput.setText(settings.getString("name"));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        saveBotNameBtn.setOnClickListener(v -> {
            String newName = botNameInput.getText().toString().trim();
            if (!newName.isEmpty()) {
                try {
                    JSONObject newSettings = new JSONObject();
                    newSettings.put("name", newName);

                    saveDataToFile(this, settingsFileName, newSettings);
                    Toast.makeText(this,
                            "Перезапустите приложение для того, чтобы настройки вступили в силу",
                            Toast.LENGTH_LONG).show();
                } catch (JSONException e) {
                    e.printStackTrace();
                    Toast.makeText(this, "Ошибка сохранения настроек", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "Имя бота не может быть пустым", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showChangeDataContent() {
        mainContent.setVisibility(View.GONE);
        changeDataContent.setVisibility(View.VISIBLE);
        devicesContent.setVisibility(View.GONE);
        settingsContent.setVisibility(View.GONE);

        // Загрузка данных в форму
        String fileName = "device_data.json";
        JSONObject deviceData = readDataFromFile(this, fileName);
        try {
            String deviceName = deviceData.getString("deviceName");
            String password = deviceData.getString("password");

            EditText deviceNameEditText = findViewById(R.id.deviceNameChange);
            EditText passwordEditText = findViewById(R.id.passwordChange);

            deviceNameEditText.setText(deviceName);
            passwordEditText.setText(password);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        // Обработчик кнопки изменения данных
        Button changeButton = findViewById(R.id.change_data);
        changeButton.setOnClickListener(view -> {
            EditText deviceNameEditText = findViewById(R.id.deviceNameChange);
            EditText passwordEditText = findViewById(R.id.passwordChange);
            String deviceName = deviceNameEditText.getText().toString();
            String password = passwordEditText.getText().toString();

            if (deviceName.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Пожалуйста, заполните все поля.", Toast.LENGTH_SHORT).show();
                return;
            }

            JSONObject jsonData = new JSONObject();
            try {
                jsonData.put("deviceName", deviceName);
                jsonData.put("password", password);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            saveDataToFile(this, "device_data.json", jsonData);
            Toast.makeText(this, "Перезапустите приложение, чтобы подтвердить данные", Toast.LENGTH_LONG).show();
        });
    }

    private void showRegistrationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_register, null);
        builder.setView(dialogView);
        builder.setTitle("Регистрация");
        builder.setCancelable(true);

        EditText etEmail = dialogView.findViewById(R.id.etEmail);
        EditText etLogin = dialogView.findViewById(R.id.etLogin);
        EditText etPassword = dialogView.findViewById(R.id.etPassword);
        EditText etConfirmPassword = dialogView.findViewById(R.id.etConfirmPassword);
        TextView tvServerResponse = dialogView.findViewById(R.id.tvServerResponse);
        Button btnRegister = dialogView.findViewById(R.id.btnRegister);

        AlertDialog dialog = builder.create();

        btnRegister.setOnClickListener(v -> {
            String email = etEmail.getText().toString().trim();
            String login = etLogin.getText().toString().trim();
            String password = etPassword.getText().toString();
            String confirmPassword = etConfirmPassword.getText().toString();

            // Валидация полей
            if (email.isEmpty() || login.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
                tvServerResponse.setText("Все поля обязательны для заполнения");
                return;
            }

            if (!password.equals(confirmPassword)) {
                tvServerResponse.setText("Пароли не совпадают");
                return;
            }

            registerUser(email, login, password, dialog, tvServerResponse);
        });

        dialog.show();
    }

    private void registerUser(String email, String login, String password, AlertDialog dialog, TextView tvResponse) {
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");

                JSONObject json = new JSONObject();
                json.put("email", email);
                json.put("login", login);
                json.put("password", password);
                json.put("mac", getUniqueDeviceId(this));

                RequestBody body = RequestBody.create(JSON, json.toString());
                Request request = new Request.Builder()
                        .url("http://blue.fnode.me:25550/register")
                        .post(body)
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body().string();

                runOnUiThread(() -> {
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if (jsonResponse.getString("status").equals("success")) {
                            pendingUserLogin = jsonResponse.getString("user_login");
                            Toast.makeText(this,
                                    jsonResponse.getString("message"),
                                    Toast.LENGTH_LONG).show();
                            dialog.dismiss();
                            openMainWindow(); // Обновляем главное окно
                        } else {
                            tvResponse.setText(jsonResponse.getString("message"));
                        }
                    } catch (JSONException e) {
                        tvResponse.setText("Ошибка обработки ответа сервера");
                        e.printStackTrace();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvResponse.setText("Ошибка при отправке запроса: " + e.getMessage());
                });
                e.printStackTrace();
            }
        }).start();
    }

    private void showLoginDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_login, null);
        builder.setView(dialogView);
        builder.setTitle("Вход");
        builder.setCancelable(true);

        EditText etLoginOrEmail = dialogView.findViewById(R.id.etLoginOrEmail);
        EditText etPassword = dialogView.findViewById(R.id.etPassword);
        TextView tvServerResponse = dialogView.findViewById(R.id.tvServerResponse);
        Button btnLogin = dialogView.findViewById(R.id.btnLogin);

        AlertDialog dialog = builder.create();

        btnLogin.setOnClickListener(v -> {
            String loginOrEmail = etLoginOrEmail.getText().toString().trim();
            String password = etPassword.getText().toString();

            // Валидация полей
            if (loginOrEmail.isEmpty() || password.isEmpty()) {
                tvServerResponse.setText("Все поля обязательны для заполнения");
                return;
            }

            loginUser(loginOrEmail, password, dialog, tvServerResponse);
        });

        dialog.show();
    }

    private void loginUser(String loginOrEmail, String password, AlertDialog dialog, TextView tvResponse) {
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");

                JSONObject json = new JSONObject();
                json.put("login", loginOrEmail);
                json.put("password", password);
                json.put("mac", getUniqueDeviceId(this));

                RequestBody body = RequestBody.create(JSON, json.toString());
                Request request = new Request.Builder()
                        .url("http://blue.fnode.me:25550/login")
                        .post(body)
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body().string();

                runOnUiThread(() -> {
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if (jsonResponse.getString("status").equals("success")) {
                            pendingUserLogin = jsonResponse.getString("user_login");
                            Toast.makeText(this,
                                    jsonResponse.getString("message"),
                                    Toast.LENGTH_LONG).show();
                            dialog.dismiss();
                            openMainWindow(); // Обновляем главное окно

                            // Можно сохранить данные устройства, если они есть
                            if (jsonResponse.has("device_info")) {
                                JSONObject deviceInfo = jsonResponse.getJSONObject("device_info");
                                // Обработка данных устройства при необходимости
                            }
                        } else {
                            tvResponse.setText(jsonResponse.getString("message"));
                        }
                    } catch (JSONException e) {
                        tvResponse.setText("Ошибка обработки ответа сервера");
                        e.printStackTrace();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvResponse.setText("Ошибка при отправке запроса: " + e.getMessage());
                });
                e.printStackTrace();
            }
        }).start();
    }

    private void logoutUser() {
        new Thread(() -> {
            try {
                String macAddress = getUniqueDeviceId(this);

                OkHttpClient client = new OkHttpClient();
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");

                JSONObject json = new JSONObject();
                json.put("MAC", macAddress);

                RequestBody body = RequestBody.create(JSON, json.toString());
                Request request = new Request.Builder()
                        .url("http://blue.fnode.me:25550/logout")
                        .post(body)
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body().string();

                runOnUiThread(() -> {
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if (jsonResponse.getString("status").equals("success")) {
                            pendingUserLogin = null;
                            Toast.makeText(this,
                                    jsonResponse.getString("message"),
                                    Toast.LENGTH_LONG).show();
                            openMainWindow(); // Обновляем главное окно
                        } else {
                            Toast.makeText(this,
                                    "Ошибка при выходе из аккаунта",
                                    Toast.LENGTH_SHORT).show();
                        }
                    } catch (JSONException e) {
                        Toast.makeText(this,
                                "Ошибка обработки ответа сервера",
                                Toast.LENGTH_SHORT).show();
                        e.printStackTrace();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this,
                            "Ошибка при отправке запроса: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
                e.printStackTrace();
            }
        }).start();
    }

    private String getUniqueDeviceId(Context context) {
        return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    }


    public void saveDataToFile(Context context, String fileName, JSONObject jsonData) {
        try (FileOutputStream fos = context.openFileOutput(fileName, Context.MODE_PRIVATE)) {
            fos.write(jsonData.toString().getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public JSONObject readDataFromFile(Context context, String fileName) {
        StringBuilder jsonString = new StringBuilder();
        try (FileInputStream fis = context.openFileInput(fileName);
             InputStreamReader isr = new InputStreamReader(fis);
             BufferedReader bufferedReader = new BufferedReader(isr)) {

            String line;
            while ((line = bufferedReader.readLine()) != null) {
                jsonString.append(line);
            }

            return new JSONObject(jsonString.toString());
        } catch (IOException | JSONException e) {
            e.printStackTrace();
            return null;
        }
    }

    public boolean isDataValid(JSONObject deviceData) {
        try {
            // Обратите внимание на правильные ключи
            return deviceData.has("deviceName") && deviceData.has("password");
        } catch (Exception e) {
            return false; // Если возникла ошибка, данные считаются некорректными
        }
    }

    public void checkAndCreateSettingsFile() {
        String fileName = "settings.json";
        File file = new File(getFilesDir(), fileName);

        if (!file.exists()) {
            try {
                JSONObject defaultSettings = new JSONObject();
                defaultSettings.put("name", "пятница");

                FileOutputStream fos = openFileOutput(fileName, Context.MODE_PRIVATE);
                fos.write(defaultSettings.toString().getBytes());
                fos.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void clearHistory() {
        new Thread(() -> {
            try {
                String macAddress = getUniqueDeviceId(this);

                OkHttpClient client = new OkHttpClient();
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");

                JSONObject json = new JSONObject();
                json.put("mac", macAddress);

                RequestBody body = RequestBody.create(JSON, json.toString());
                Request request = new Request.Builder()
                        .url("http://blue.fnode.me:25550/clear_history")
                        .post(body)
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body().string();

                runOnUiThread(() -> {
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if (jsonResponse.getString("status").equals("success")) {
                            resultView.setText("");
                            Toast.makeText(this,
                                    jsonResponse.getString("message"),
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this,
                                    "Ошибка при очистке истории",
                                    Toast.LENGTH_SHORT).show();
                        }
                    } catch (JSONException e) {
                        Toast.makeText(this,
                                "Ошибка обработки ответа сервера",
                                Toast.LENGTH_SHORT).show();
                        e.printStackTrace();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this,
                            "Ошибка при отправке запроса: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
                e.printStackTrace();
            }
        }).start();
    }

    private void executeInBackground(JSONObject commandMessage) {
        try {

            Intent serviceIntent = new Intent(this, MyForegroundService.class);
            serviceIntent.putExtra("command", commandMessage.toString());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e("Background", "Error starting service", e);
            runOnUiThread(() -> {
                Toast.makeText(this, "Ошибка выполнения команды", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private JSONArray getRunningAppsWithTitles() {
        JSONArray runningApps = new JSONArray();
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        PackageManager pm = getPackageManager();

        // Получаем список процессов
        List<ActivityManager.RunningAppProcessInfo> runningProcesses = am.getRunningAppProcesses();
        if (runningProcesses != null) {
            for (ActivityManager.RunningAppProcessInfo processInfo : runningProcesses) {
                // Проверяем, находится ли процесс на переднем плане или видим
                if (processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                        processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {

                    // Получаем список пакетов для этого процесса
                    for (String pkg : processInfo.pkgList) {
                        try {
                            ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                            String appName = pm.getApplicationLabel(appInfo).toString();

                            // Добавляем только пользовательские приложения (не системные)
                            if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                                runningApps.put(appName + " (" + pkg + ")");
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            // Игнорируем пакеты, которые не найдены
                        }
                    }
                }
            }
        }

        // Дополнительно получаем текущее приложение на переднем плане (для Android 21+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
                long time = System.currentTimeMillis();
                List<UsageStats> appList = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60 * 60, time);

                if (appList != null && !appList.isEmpty()) {
                    for (UsageStats usageStats : appList) {
                        try {
                            String packageName = usageStats.getPackageName();
                            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                            String appName = pm.getApplicationLabel(appInfo).toString();

                            // Проверяем, является ли приложение пользовательским (не системным)
                            if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                                // Проверяем, нет ли уже этого приложения в списке
                                boolean alreadyExists = false;
                                for (int i = 0; i < runningApps.length(); i++) {
                                    if (runningApps.getString(i).contains(packageName)) {
                                        alreadyExists = true;
                                        break;
                                    }
                                }
                                if (!alreadyExists) {
                                    runningApps.put(appName + " (" + packageName + ")");
                                }
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            // Игнорируем системные приложения
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("RunningApps", "Error getting usage stats", e);
            }
        }

        return runningApps;
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAndRequestPermissions();
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    private void initModel() {
        StorageService.unpack(this, "model-en-us", "model",
                (model) -> {
                    this.model = model;
                    setUiState(STATE_READY);
                },
                (exception) -> setErrorState("Failed to unpack the model" + exception.getMessage()));
    }

    // В методе onCreate() или другом подходящем месте
    private static final int PERMISSION_REQUEST_CODE = 1002;

    public void checkAudioPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS)
                    != PackageManager.PERMISSION_GRANTED) {

                requestPermissions(
                        new String[]{Manifest.permission.MODIFY_AUDIO_SETTINGS},
                        PERMISSION_REQUEST_CODE
                );
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Разрешения получены", Toast.LENGTH_SHORT).show();
            }
        }
    }
    @Override
    public void onDestroy() {

        isDestroyed = true; // Помечаем, что Activity уничтожается

        // Остановка всех сервисов и соединений
        stopService(new Intent(this, MyForegroundService.class));

        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
            speechService = null;
        }

        if (speechStreamService != null) {
            speechStreamService.stop();
        }

        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }

        // Корректное закрытие WebSocket
        if (webSocket != null) {
            try {
                webSocket.close(1000, "Activity destroyed");
            } catch (Exception e) {
                Log.e("WebSocket", "Error closing WebSocket", e);
            }
            webSocket = null;
        }

        // Остановка клиента OkHttp
        if (client != null) {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
            client = null;
        }

        // Остановка проверки соединения
        connectionHandler.removeCallbacksAndMessages(null);

        super.onDestroy();
        instance = null;
    }


    @Override
    public void onResult(String hypothesis) {
        String spokenText = "";
        try {
            JSONObject jsonObject = new JSONObject(hypothesis);
            spokenText = jsonObject.getString("text");
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (spokenText.toLowerCase().contains(botName)) {
            String finalSpokenText = spokenText;
            runOnUiThread(() -> {
                resultView.append("Вы: " + finalSpokenText + "\n");
                scrollToBottom();
            });
            try {
                JSONObject commandMessage = new JSONObject();
                commandMessage.put("command", finalSpokenText);
                commandMessage.put("mac", getUniqueDeviceId(this));
                commandMessage.put("name", botName);
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.getDefault());
                String timestamp = sdf.format(new java.util.Date())
                        .replaceAll("(\\d{2})(\\d{2})$", "$1:$2"); // Преобразуем +0300 в +03:00
                commandMessage.put("timestamp", timestamp);

                sendEncodedMessage(commandMessage);
            } catch (JSONException e) {
                e.printStackTrace();
            }

        }
    }

    public void updateResultText(String text)
    {
        runOnUiThread(() -> {
            resultView.append(text);
            scrollToBottom();
        });
    }

    private void scrollToBottom() {
        resultView.post(() -> {
            int scrollAmount = resultView.getLayout().getLineTop(resultView.getLineCount())
                    - resultView.getHeight();
            if (scrollAmount > 0) {
                resultView.scrollTo(0, scrollAmount);
            } else {
                resultView.scrollTo(0, 0);
            }
        });
    }

    public JSONArray getInstalledPackages() {
        JSONArray programs = new JSONArray();
        try {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);

            for (ApplicationInfo packageInfo : packages) {
                // Фильтруем системные приложения при необходимости
                if ((packageInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    programs.put(packageInfo.packageName);
                }
            }
        } catch (Exception e) {
            Log.e("PackageManager", "Error getting installed apps", e);
        }
        return programs;
    }

    @Override
    public void onFinalResult(String hypothesis) {
        //123
    }

    @Override
    public void onPartialResult(String hypothesis) {
        //123
    }

    @Override
    public void onError(Exception e) {
        setErrorState(e.getMessage());
    }

    @Override
    public void onTimeout() {
        setUiState(STATE_DONE);
    }

    public void setUiState(int state) {
        switch (state) {
            case STATE_START:
                findViewById(R.id.recognize_mic).setEnabled(false);
                break;
            case STATE_READY:
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_mic).setEnabled(true);
                break;
            case STATE_DONE:
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_mic).setEnabled(true);
                break;
            case STATE_FILE:
                findViewById(R.id.recognize_mic).setEnabled(false);
                break;
            case STATE_MIC:
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.stop_microphone);
                findViewById(R.id.recognize_mic).setEnabled(true);
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + state);
        }
    }

    private void setErrorState(String message) {
        resultView.setText(message);
        ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
        findViewById(R.id.recognize_mic).setEnabled(false);
    }

    void recognizeMicrophone() {
        if (speechService != null) {
            // Stop the microphone if it's currently running
            setUiState(STATE_DONE);
            speechService.stop();
            speechService = null; // Clear reference
        } else {
            // Start the microphone
            setUiState(STATE_MIC);
            try {
                Recognizer rec = new Recognizer(model, 16000.0f);
                speechService = new SpeechService(rec, 16000.0f);
                speechService.startListening(this);
            } catch (IOException e) {
                setErrorState(e.getMessage());
            }
        }
    }

}
