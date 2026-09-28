package org.vosk.demo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class FridayActivity extends Activity {

    private static FridayActivity instance;
    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;
    private static final int PICK_IMAGE_REQUEST = 1001;

    private WebSocket webSocket;
    public boolean isRegistrationWindowOpen = false;

    private LinearLayout mainContent;
    private ScrollView changeDataContent;
    private ScrollView devicesContent;
    private ScrollView settingsContent;

    // Chat UI elements
    private RecyclerView chatRecyclerView;
    private ChatAdapter chatAdapter;
    private final List<ChatMessage> chatMessages = new ArrayList<>();
    private TextView currentDialogTitle;

    private String botName = "пятница";
    private Handler connectionHandler = new Handler();
    private static final long CONNECTION_CHECK_INTERVAL = 30000;
    public OkHttpClient client;
    public boolean isDestroyed = false;

    public String pendingHistory = null;
    private String pendingUserLogin = null;

    private EditText messageEditText;
    private ImageButton sendButton;
    private ImageButton attachButton;
    private ImageButton micButton;
    private TextView micStatusText;

    private boolean isBotSpeaking = false;
    private BroadcastReceiver botSpeakingReceiver;
    private String settingsFileName = "settings.json";

    private LinearLayout photoPreviewContainer;
    private ImageView selectedPhotoView;
    private ImageButton removePhotoButton;
    private Uri selectedImageUri = null;
    private Bitmap selectedImageBitmap = null;

    // --- Audio Streaming (AudioRecord) ---
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread = null;
    private String currentStreamId = null;
    private ChatMessage currentVoiceUserMessage = null;
    private ChatMessage currentBotResponseMsg = null;

    // --- Multi-Dialogs ---
    public static class DialogItem {
        public String id;
        public String name;

        public DialogItem(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final List<DialogItem> userDialogs = new ArrayList<>();
    private String currentDialogId = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;

        checkAndCreateSettingsFile();

        JSONObject settings = readDataFromFile(this, settingsFileName);
        try {
            if (settings != null && settings.has("name")) {
                botName = settings.getString("name").toLowerCase();
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        verifySavedToken();
        initWebSocketConnection();
        startConnectionChecker();

        botSpeakingReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (MyForegroundService.ACTION_BOT_SPEAKING.equals(intent.getAction())) {
                    isBotSpeaking = intent.getBooleanExtra(MyForegroundService.EXTRA_IS_SPEAKING, false);
                }
            }
        };

        IntentFilter filter = new IntentFilter(MyForegroundService.ACTION_BOT_SPEAKING);
        registerReceiver(botSpeakingReceiver, filter);

        openMainWindow();
    }

    public static FridayActivity getInstance() {
        return instance;
    }

    public void initWebSocketConnection() {
        if (isDestroyed) return;

        if (client == null) {
            client = new OkHttpClient();
        }
        Request request = new Request.Builder().url("wss://friday-assistant.ru/ws").build();
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

                String token = getSavedToken();
                if (token != null && !token.isEmpty()) {
                    JSONObject accountSync = new JSONObject();
                    accountSync.put("type", "account_sync");
                    accountSync.put("token", token);
                    accountSync.put("mac", macAddress);
                    sendEncodedMessage(accountSync);
                }
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
            initWebSocketConnection();
        } else {
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

    public void sendEncodedMessage(JSONObject message) {
        if (webSocket != null) {
            try {
                String jsonString = message.toString();
                byte[] data = jsonString.getBytes("UTF-8");
                String encodedData = Base64.encodeToString(data, Base64.NO_WRAP);
                webSocket.send(encodedData);
            } catch (UnsupportedEncodingException e) {
                Log.e("WebSocket", "Encoding error", e);
                webSocket.send(message.toString());
            }
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
                String msgType = jsonMessage.optString("type");

                if ("audio_chunk".equals(msgType)) {
                    Intent serviceIntent = new Intent(this, MyForegroundService.class);
                    serviceIntent.setAction("AUDIO_CHUNK");
                    serviceIntent.putExtra("audio_base64", jsonMessage.optString("audio_base64"));
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }
                    return;
                }

                if ("text_chunk".equals(msgType) || "bot_chunk".equals(msgType)) {
                    String chunkText = jsonMessage.optString("text", "");
                    if (!chunkText.isEmpty()) {
                        appendBotResponse(chunkText);
                    }
                    return;
                }

                if ("transcription".equals(msgType) || "recognized_text".equals(msgType)) {
                    String transcribedText = jsonMessage.optString("text", "");
                    String targetUiId = jsonMessage.optString("ui_msg_id", "");
                    updateVoiceUserMessage(targetUiId, transcribedText);
                    return;
                }

                if ("dialog_renamed".equals(msgType) || "dialog_created".equals(msgType)) {
                    loadUserDialogs();
                    return;
                }

                if ("new_message".equals(msgType)) {
                    executeInBackground(jsonMessage);
                    return;
                }

                if (encodedMessage.contains("Имя устройства уже занято.")) {
                    Toast.makeText(this, "Это имя устройства уже занято. Пожалуйста, выберите другое.", Toast.LENGTH_LONG).show();
                    if (!isRegistrationWindowOpen) openRegistrationWindow();
                } else if (encodedMessage.contains("Данные успешно обработаны")) {
                    try {
                        JSONObject jsonData = new JSONObject(encodedMessage);
                        if (isRegistrationWindowOpen) {
                            EditText deviceNameEditText = findViewById(R.id.deviceName);
                            EditText passwordEditText = findViewById(R.id.password);
                            JSONObject deviceData = new JSONObject();
                            deviceData.put("deviceName", deviceNameEditText.getText().toString());
                            deviceData.put("password", passwordEditText.getText().toString());
                            saveDataToFile(this, "device_data.json", deviceData);
                            Toast.makeText(this, "Данные успешно сохранены", Toast.LENGTH_LONG).show();
                        }
                        if (jsonData.has("history")) pendingHistory = jsonData.getJSONArray("history").toString();
                        if (jsonData.has("user_login")) pendingUserLogin = jsonData.getString("user_login");

                        openMainWindow();
                    } catch (JSONException e) {
                        Toast.makeText(this, "Ошибка обработки данных", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    // Check if it's a general response message
                    String respText = jsonMessage.optString("response", jsonMessage.optString("text", ""));
                    if (!respText.isEmpty()) {
                        appendBotResponse(respText);
                    } else {
                        Toast.makeText(this, encodedMessage, Toast.LENGTH_SHORT).show();
                    }
                }
            } catch (JSONException e) {
                Log.e("MessageHandler", "Error parsing message", e);
            }
        });
    }

    @SuppressLint("WrongViewCast")
    private void openMainWindow() {
        isRegistrationWindowOpen = false;
        setContentView(R.layout.main);

        mainContent = findViewById(R.id.main_content);
        changeDataContent = findViewById(R.id.change_data_content);
        devicesContent = findViewById(R.id.devices_content);
        settingsContent = findViewById(R.id.settings_content);

        // Header dialog controls
        ImageButton btnNewDialog = findViewById(R.id.btn_new_dialog);
        ImageButton btnDeleteDialog = findViewById(R.id.btn_delete_dialog);
        if (btnNewDialog != null) btnNewDialog.setOnClickListener(v -> createNewDialog());
        if (btnDeleteDialog != null) btnDeleteDialog.setOnClickListener(v -> deleteCurrentDialog());

        // Nav bar buttons
        View mainBtn = findViewById(R.id.main_btn);
        View dataWindowBtn = findViewById(R.id.data_window_btn);
        View devicesBtn = findViewById(R.id.devices_btn);
        View settingsBtn = findViewById(R.id.settings_btn);

        if (mainBtn != null) mainBtn.setOnClickListener(v -> showMainContent());
        if (dataWindowBtn != null) dataWindowBtn.setOnClickListener(v -> showChangeDataContent());
        if (devicesBtn != null) devicesBtn.setOnClickListener(v -> showDevicesContent());
        if (settingsBtn != null) settingsBtn.setOnClickListener(v -> showSettingsContent());

        // RecyclerView Setup for Chat Messages
        chatRecyclerView = findViewById(R.id.chat_recycler_view);
        if (chatRecyclerView != null) {
            LinearLayoutManager layoutManager = new LinearLayoutManager(this);
            layoutManager.setStackFromEnd(true);
            chatRecyclerView.setLayoutManager(layoutManager);
            chatAdapter = new ChatAdapter(chatMessages);
            chatRecyclerView.setAdapter(chatAdapter);
        }

        updateUserUI();

        messageEditText = findViewById(R.id.message_edit_text);
        sendButton = findViewById(R.id.send_button);
        if (sendButton != null) sendButton.setOnClickListener(v -> sendTextMessage());

        if (messageEditText != null) {
            messageEditText.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendTextMessage();
                    return true;
                }
                return false;
            });
        }

        attachButton = findViewById(R.id.attach_button);
        if (attachButton != null) attachButton.setOnClickListener(v -> openImagePicker());

        photoPreviewContainer = findViewById(R.id.photo_preview_container);
        selectedPhotoView = findViewById(R.id.selected_photo_view);
        removePhotoButton = findViewById(R.id.remove_photo_button);
        if (removePhotoButton != null) removePhotoButton.setOnClickListener(v -> removeSelectedPhoto());

        micButton = findViewById(R.id.recognize_mic);
        micStatusText = findViewById(R.id.tv_mic_status);
        if (micButton != null) micButton.setOnClickListener(view -> toggleAudioRecording());

        showMainContent();

        if (!isMyServiceRunning(MyForegroundService.class)) {
            Intent serviceIntent = new Intent(this, MyForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }

        if (pendingHistory != null) {
            displayHistory(pendingHistory);
            pendingHistory = null;
        }

        String token = getSavedToken();
        if (token != null && !token.isEmpty()) {
            loadUserDialogs();
        }
    }

    private void updateUserUI() {
        Button btnRegister = findViewById(R.id.btn_register);
        Button btnLogin = findViewById(R.id.btn_login);
        Button btnUserProfile = findViewById(R.id.btn_user_profile);
        LinearLayout dialogsLayout = findViewById(R.id.dialogs_layout);

        String savedLogin = pendingUserLogin;
        if (savedLogin == null || savedLogin.isEmpty()) {
            JSONObject settings = readDataFromFile(this, settingsFileName);
            if (settings != null && settings.has("user_login")) {
                savedLogin = settings.optString("user_login");
            }
        }

        if (savedLogin != null && !savedLogin.isEmpty()) {
            if (btnUserProfile != null) {
                btnUserProfile.setText(savedLogin);
                btnUserProfile.setVisibility(View.VISIBLE);
                final String currentLogin = savedLogin;
                btnUserProfile.setOnClickListener(v -> {
                    new AlertDialog.Builder(this)
                            .setTitle("Выход из аккаунта")
                            .setMessage("Вы уверены, что хотите выйти из аккаунта " + currentLogin + "?")
                            .setPositiveButton("Выйти", (dialog, which) -> logoutUser())
                            .setNegativeButton("Отмена", null)
                            .show();
                });
            }
            if (btnRegister != null) btnRegister.setVisibility(View.GONE);
            if (btnLogin != null) btnLogin.setVisibility(View.GONE);
            if (dialogsLayout != null) dialogsLayout.setVisibility(View.VISIBLE);
        } else {
            if (btnRegister != null) {
                btnRegister.setVisibility(View.VISIBLE);
                btnRegister.setOnClickListener(v -> showRegistrationDialog());
            }
            if (btnLogin != null) {
                btnLogin.setVisibility(View.VISIBLE);
                btnLogin.setOnClickListener(v -> showLoginDialog());
            }
            if (btnUserProfile != null) btnUserProfile.setVisibility(View.GONE);
            if (dialogsLayout != null) dialogsLayout.setVisibility(View.GONE);
        }
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

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "Выберите фото"), PICK_IMAGE_REQUEST);
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(this, "Установите файловый менеджер", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                Uri selectedUri = data.getData();
                handleSelectedImage(selectedUri);
            }
        }
    }

    private void handleSelectedImage(Uri imageUri) {
        try {
            if (selectedImageBitmap != null) {
                selectedImageBitmap.recycle();
                selectedImageBitmap = null;
            }
            this.selectedImageUri = imageUri;
            InputStream imageStream = getContentResolver().openInputStream(imageUri);
            selectedImageBitmap = BitmapFactory.decodeStream(imageStream);
            imageStream.close();

            runOnUiThread(() -> {
                if (selectedPhotoView != null) selectedPhotoView.setImageBitmap(selectedImageBitmap);
                if (photoPreviewContainer != null) photoPreviewContainer.setVisibility(View.VISIBLE);
                scrollToBottom();
            });
            Toast.makeText(this, "Фото выбрано", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e("PhotoSelection", "Ошибка загрузки фото", e);
            Toast.makeText(this, "Ошибка загрузки фото", Toast.LENGTH_SHORT).show();
        }
    }

    private void removeSelectedPhoto() {
        if (selectedImageBitmap != null) {
            selectedImageBitmap.recycle();
            selectedImageBitmap = null;
        }
        selectedImageUri = null;
        if (photoPreviewContainer != null) photoPreviewContainer.setVisibility(View.GONE);
        if (selectedPhotoView != null) selectedPhotoView.setImageDrawable(null);
    }

    public String getSavedToken() {
        JSONObject settings = readDataFromFile(this, settingsFileName);
        if (settings != null && settings.has("user_token")) {
            try {
                return settings.getString("user_token");
            } catch (JSONException e) {}
        }
        return null;
    }

    public String getSelectedVoice() {
        JSONObject settings = readDataFromFile(this, settingsFileName);
        if (settings != null && settings.has("voice_type")) {
            try {
                return settings.getString("voice_type");
            } catch (JSONException e) {}
        }
        return "Aoede";
    }

    public void saveAuthData(String token, String userLogin) {
        try {
            JSONObject settings = readDataFromFile(this, settingsFileName);
            if (settings == null) settings = new JSONObject();
            if (token != null && !token.isEmpty()) settings.put("user_token", token);
            else settings.remove("user_token");

            if (userLogin != null && !userLogin.isEmpty()) settings.put("user_login", userLogin);
            else settings.remove("user_login");

            saveDataToFile(this, settingsFileName, settings);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void verifySavedToken() {
        String token = getSavedToken();
        if (token == null || token.isEmpty()) return;

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
                    pendingUserLogin = json.optString("user_login");
                    saveAuthData(token, pendingUserLogin);
                    runOnUiThread(() -> {
                        updateUserUI();
                        loadUserDialogs();
                    });
                } else {
                    saveAuthData(null, null);
                    pendingUserLogin = null;
                    runOnUiThread(() -> updateUserUI());
                }
            } catch (Exception e) {
                Log.e("VerifyToken", "Error verifying token", e);
            }
        }).start();
    }

    private void addChatMessage(ChatMessage msg) {
        chatMessages.add(msg);
        if (chatAdapter != null) {
            chatAdapter.notifyItemInserted(chatMessages.size() - 1);
            scrollToBottom();
        }
    }

    private void sendTextMessage() {
        String message = messageEditText.getText().toString().trim();
        if (message.isEmpty() && selectedImageBitmap == null) return;

        try {
            JSONObject messageJson = new JSONObject();
            messageJson.put("command", message);
            messageJson.put("mac", getUniqueDeviceId(this));
            messageJson.put("name", botName);
            messageJson.put("type", "текстовое сообщение");
            messageJson.put("voice_type", getSelectedVoice());

            if (currentDialogId != null && !currentDialogId.isEmpty()) {
                messageJson.put("dialog_id", currentDialogId);
            }
            String token = getSavedToken();
            if (token != null && !token.isEmpty()) {
                messageJson.put("token", token);
            }

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.getDefault());
            String timestamp = sdf.format(new Date()).replaceAll("(\\d{2})(\\d{2})$", "$1:$2");
            messageJson.put("timestamp", timestamp);

            if (selectedImageUri != null) {
                messageJson.put("screenshot", encodeImageToBase64(selectedImageBitmap));
            }

            sendEncodedMessage(messageJson);

            // Add user message to RecyclerView
            ChatMessage userMsg = ChatMessage.createUser(message.isEmpty() ? "[Фото]" : message);
            addChatMessage(userMsg);

            // Prepare bot response holder
            currentBotResponseMsg = null;

            messageEditText.setText("");
            removeSelectedPhoto();

        } catch (JSONException e) {
            Log.e("TextMessage", "Error creating message JSON", e);
            Toast.makeText(this, "Ошибка создания сообщения", Toast.LENGTH_SHORT).show();
        }
    }

    private String encodeImageToBase64(Bitmap bitmap) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        return Base64.encodeToString(byteArray, Base64.DEFAULT);
    }

    private void displayHistory(String historyJson) {
        if (historyJson == null || historyJson.trim().isEmpty()) return;

        runOnUiThread(() -> {
            try {
                JSONArray historyArray = new JSONArray(historyJson);
                chatMessages.clear();

                for (int i = 0; i < historyArray.length(); i++) {
                    JSONObject message = historyArray.getJSONObject(i);
                    String sender = message.optString("sender", "");
                    String text = message.optString("text", "");

                    if (sender.isEmpty() || text.isEmpty()) continue;

                    boolean isUser = sender.equalsIgnoreCase("user") || sender.equalsIgnoreCase("пользователь") || sender.equalsIgnoreCase("вы");
                    if (isUser) {
                        chatMessages.add(ChatMessage.createUser(text));
                    } else {
                        chatMessages.add(ChatMessage.createBot(text));
                    }
                }
                if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
                scrollToBottom();
            } catch (JSONException e) {
                Log.e("History", "Error parsing history", e);
            }
        });
    }

    private void showMainContent() {
        if (mainContent != null) mainContent.setVisibility(View.VISIBLE);
        if (changeDataContent != null) changeDataContent.setVisibility(View.GONE);
        if (devicesContent != null) devicesContent.setVisibility(View.GONE);
        if (settingsContent != null) settingsContent.setVisibility(View.GONE);
    }

    private void showDevicesContent() {
        if (mainContent != null) mainContent.setVisibility(View.GONE);
        if (changeDataContent != null) changeDataContent.setVisibility(View.GONE);
        if (devicesContent != null) devicesContent.setVisibility(View.VISIBLE);
        if (settingsContent != null) settingsContent.setVisibility(View.GONE);

        Button connectDeviceBtn = findViewById(R.id.btn_connect_device);
        LinearLayout accountDevicesContainer = findViewById(R.id.account_devices_container);
        LinearLayout connectedDevicesContainer = findViewById(R.id.connected_devices_container);

        if (accountDevicesContainer != null) accountDevicesContainer.removeAllViews();
        if (connectedDevicesContainer != null) connectedDevicesContainer.removeAllViews();

        if (connectDeviceBtn != null) connectDeviceBtn.setOnClickListener(v -> showConnectDeviceDialog());

        String macAddress = getUniqueDeviceId(this);

        new Thread(() -> {
            try {
                OkHttpClient httpClient = new OkHttpClient();
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");

                JSONObject json = new JSONObject();
                json.put("mac", macAddress);

                RequestBody body = RequestBody.create(JSON, json.toString());
                Request request = new Request.Builder()
                        .url("https://friday-assistant.ru/get_devices")
                        .post(body)
                        .build();

                Response response = httpClient.newCall(request).execute();
                String responseBody = response.body().string();

                runOnUiThread(() -> {
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);

                        if (jsonResponse.getString("status").equals("success")) {
                            JSONArray accountDevices = jsonResponse.getJSONArray("account_devices");
                            for (int i = 0; i < accountDevices.length(); i++) {
                                JSONObject device = accountDevices.getJSONObject(i);
                                addDeviceView(accountDevicesContainer, device, true);
                            }

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
        if (container == null) return;
        try {
            LinearLayout deviceLayout = new LinearLayout(this);
            deviceLayout.setOrientation(LinearLayout.HORIZONTAL);
            deviceLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            deviceLayout.setPadding(24, 24, 24, 24);
            deviceLayout.setBackgroundResource(R.drawable.device_item_background);

            String deviceName = device.optString("DeviceName", "Неизвестное устройство");
            String macAddress = device.optString("MacAddress", "MAC не указан");
            boolean isOnline = device.optBoolean("IsOnline", false);

            float textWeight = isAccountDevice ? 1.0f : 0.7f;

            LinearLayout textContainer = new LinearLayout(this);
            textContainer.setOrientation(LinearLayout.VERTICAL);
            textContainer.setLayoutParams(new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    textWeight));
            textContainer.setPadding(0, 0, 16, 0);

            int textColor = Color.parseColor("#F1F1F5");
            int macTextColor = Color.parseColor("#9090A6");
            int textSize = 16;
            int macTextSize = 14;

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

            LinearLayout statusContainer = new LinearLayout(this);
            statusContainer.setOrientation(LinearLayout.HORIZONTAL);
            statusContainer.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

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

            TextView separator = new TextView(this);
            separator.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            separator.setText(" - ");
            separator.setTextColor(macTextColor);
            separator.setTextSize(TypedValue.COMPLEX_UNIT_SP, macTextSize);
            statusContainer.addView(separator);

            TextView statusView = new TextView(this);
            statusView.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            statusView.setText(isOnline ? "онлайн" : "офлайн");
            statusView.setTextColor(isOnline ? Color.parseColor("#4CAF50") : Color.parseColor("#E53935"));
            statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, macTextSize);
            statusContainer.addView(statusView);

            textContainer.addView(statusContainer);
            deviceLayout.addView(textContainer);

            if (!isAccountDevice) {
                Button disconnectBtn = new Button(this);
                LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                btnParams.gravity = Gravity.CENTER_VERTICAL;
                disconnectBtn.setLayoutParams(btnParams);

                disconnectBtn.setText("Отключить");
                disconnectBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                disconnectBtn.setPadding(16, 8, 16, 8);
                disconnectBtn.setBackgroundResource(R.drawable.disconnect_button_bg);
                disconnectBtn.setTextColor(Color.WHITE);

                disconnectBtn.setOnClickListener(v -> {
                    try {
                        String macToDisconnect = device.optString("MacAddress", "");
                        if (!macToDisconnect.isEmpty()) {
                            disconnectDevice(macToDisconnect);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
                deviceLayout.addView(disconnectBtn);
            }

            container.addView(deviceLayout);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void disconnectDevice(String macAddress) {
        new Thread(() -> {
            try {
                OkHttpClient httpClient = new OkHttpClient();
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");

                JSONObject json = new JSONObject();
                json.put("requester_mac", getUniqueDeviceId(this));
                json.put("target_mac", macAddress);

                RequestBody body = RequestBody.create(JSON, json.toString());
                Request request = new Request.Builder()
                        .url("https://friday-assistant.ru/disconnect_device")
                        .post(body)
                        .build();

                Response response = httpClient.newCall(request).execute();
                String responseBody = response.body().string();

                runOnUiThread(() -> {
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if (jsonResponse.getString("status").equals("success")) {
                            Toast.makeText(this, "Устройство отключено", Toast.LENGTH_SHORT).show();
                            showDevicesContent();
                        } else {
                            Toast.makeText(this, "Ошибка: " + jsonResponse.optString("message", "Не удалось отключить устройство"),
                                    Toast.LENGTH_SHORT).show();
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Ошибка при отключении устройства", Toast.LENGTH_SHORT).show());
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

            progressBar.setVisibility(View.VISIBLE);
            btnConnect.setVisibility(View.GONE);
            tvResponse.setVisibility(View.GONE);

            connectToDevice(deviceName, password, (success, message) -> {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnConnect.setVisibility(View.VISIBLE);

                    tvResponse.setText(message);
                    tvResponse.setTextColor(success ? Color.GREEN : Color.RED);
                    tvResponse.setVisibility(View.VISIBLE);

                    if (success) {
                        new Handler().postDelayed(() -> {
                            showDevicesContent();
                            dialog.dismiss();
                        }, 1000);
                    }
                });
            });
        });
    }

    public interface ConnectDeviceCallback {
        void onComplete(boolean success, String message);
    }

    private void connectToDevice(String deviceName, String password, ConnectDeviceCallback callback) {
        new Thread(() -> {
            try {
                OkHttpClient httpClient = new OkHttpClient();
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");

                JSONObject json = new JSONObject();
                json.put("MAC", getUniqueDeviceId(this));
                json.put("DeviceName", deviceName);
                json.put("Password", password);

                RequestBody body = RequestBody.create(JSON, json.toString());
                Request request = new Request.Builder()
                        .url("https://friday-assistant.ru/connect_device")
                        .post(body)
                        .build();

                Response response = httpClient.newCall(request).execute();
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
        if (mainContent != null) mainContent.setVisibility(View.GONE);
        if (changeDataContent != null) changeDataContent.setVisibility(View.GONE);
        if (devicesContent != null) devicesContent.setVisibility(View.GONE);
        if (settingsContent != null) settingsContent.setVisibility(View.VISIBLE);

        Spinner voiceSpinner = findViewById(R.id.bot_voice_spinner);
        Button saveVoiceBtn = findViewById(R.id.save_bot_voice_btn);

        String currentVoice = getSelectedVoice();
        String[] voiceKeys = new String[]{"Aoede", "Puck", "Kore", "Charon"};
        if (voiceSpinner != null) {
            for (int i = 0; i < voiceKeys.length; i++) {
                if (voiceKeys[i].equalsIgnoreCase(currentVoice)) {
                    voiceSpinner.setSelection(i);
                    break;
                }
            }
        }

        if (saveVoiceBtn != null) {
            saveVoiceBtn.setOnClickListener(v -> {
                if (voiceSpinner != null) {
                    int pos = voiceSpinner.getSelectedItemPosition();
                    if (pos >= 0 && pos < voiceKeys.length) {
                        String selectedVoiceKey = voiceKeys[pos];
                        try {
                            JSONObject settings = readDataFromFile(this, settingsFileName);
                            if (settings == null) settings = new JSONObject();
                            settings.put("voice_type", selectedVoiceKey);
                            saveDataToFile(this, settingsFileName, settings);
                            Toast.makeText(this, "Голос сохранен: " + selectedVoiceKey, Toast.LENGTH_SHORT).show();
                        } catch (JSONException e) {
                            e.printStackTrace();
                            Toast.makeText(this, "Ошибка сохранения настроек", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });
        }
    }

    private void showChangeDataContent() {
        if (mainContent != null) mainContent.setVisibility(View.GONE);
        if (changeDataContent != null) changeDataContent.setVisibility(View.VISIBLE);
        if (devicesContent != null) devicesContent.setVisibility(View.GONE);
        if (settingsContent != null) settingsContent.setVisibility(View.GONE);

        String fileName = "device_data.json";
        JSONObject deviceData = readDataFromFile(this, fileName);
        try {
            if (deviceData != null) {
                String deviceName = deviceData.optString("deviceName", "");
                String password = deviceData.optString("password", "");

                EditText deviceNameEditText = findViewById(R.id.deviceNameChange);
                EditText passwordEditText = findViewById(R.id.passwordChange);

                if (deviceNameEditText != null) deviceNameEditText.setText(deviceName);
                if (passwordEditText != null) passwordEditText.setText(password);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        Button changeButton = findViewById(R.id.change_data);
        if (changeButton != null) {
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
                OkHttpClient httpClient = new OkHttpClient();
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");

                JSONObject json = new JSONObject();
                json.put("email", email);
                json.put("login", login);
                json.put("password", password);
                json.put("mac", getUniqueDeviceId(this));

                RequestBody body = RequestBody.create(JSON, json.toString());
                Request request = new Request.Builder()
                        .url("https://friday-assistant.ru/register")
                        .post(body)
                        .build();

                Response response = httpClient.newCall(request).execute();
                String responseBody = response.body().string();

                runOnUiThread(() -> {
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if (jsonResponse.getString("status").equals("success")) {
                            pendingUserLogin = login;
                            String token = jsonResponse.optString("token");
                            saveAuthData(token, pendingUserLogin);

                            Toast.makeText(this, jsonResponse.getString("message"), Toast.LENGTH_LONG).show();
                            dialog.dismiss();
                            openMainWindow();
                            loadUserDialogs();
                        } else {
                            tvResponse.setText(jsonResponse.getString("message"));
                        }
                    } catch (JSONException e) {
                        tvResponse.setText("Ошибка обработки ответа сервера");
                        e.printStackTrace();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> tvResponse.setText("Ошибка при отправке запроса: " + e.getMessage()));
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
        TextView tvForgotPassword = dialogView.findViewById(R.id.tvForgotPassword);
        TextView tvServerResponse = dialogView.findViewById(R.id.tvServerResponse);
        Button btnLogin = dialogView.findViewById(R.id.btnLogin);

        AlertDialog dialog = builder.create();

        tvForgotPassword.setOnClickListener(v -> {
            dialog.dismiss();
            Intent intent = new Intent(FridayActivity.this, RecoveryPasswordActivity.class);
            startActivity(intent);
        });

        btnLogin.setOnClickListener(v -> {
            String loginOrEmail = etLoginOrEmail.getText().toString().trim();
            String password = etPassword.getText().toString();

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
                OkHttpClient httpClient = new OkHttpClient();
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");

                JSONObject json = new JSONObject();
                json.put("login", loginOrEmail);
                json.put("password", password);
                json.put("mac", getUniqueDeviceId(this));

                RequestBody body = RequestBody.create(JSON, json.toString());
                Request request = new Request.Builder()
                        .url("https://friday-assistant.ru/login")
                        .post(body)
                        .build();

                Response response = httpClient.newCall(request).execute();
                String responseBody = response.body().string();

                runOnUiThread(() -> {
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if (jsonResponse.getString("status").equals("success")) {
                            pendingUserLogin = jsonResponse.optString("user_login");
                            String token = jsonResponse.optString("token");
                            saveAuthData(token, pendingUserLogin);

                            if (token != null && !token.isEmpty()) {
                                try {
                                    JSONObject syncMsg = new JSONObject();
                                    syncMsg.put("type", "account_sync");
                                    syncMsg.put("token", token);
                                    syncMsg.put("mac", getUniqueDeviceId(this));
                                    sendEncodedMessage(syncMsg);
                                } catch (Exception e) {}
                            }

                            Toast.makeText(this, jsonResponse.getString("message"), Toast.LENGTH_LONG).show();
                            dialog.dismiss();
                            openMainWindow();
                            loadUserDialogs();
                        } else {
                            tvResponse.setText(jsonResponse.getString("message"));
                        }
                    } catch (JSONException e) {
                        tvResponse.setText("Ошибка обработки ответа сервера");
                        e.printStackTrace();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> tvResponse.setText("Ошибка при отправке запроса: " + e.getMessage()));
                e.printStackTrace();
            }
        }).start();
    }

    private void logoutUser() {
        new Thread(() -> {
            try {
                String macAddress = getUniqueDeviceId(this);

                OkHttpClient httpClient = new OkHttpClient();
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");

                JSONObject json = new JSONObject();
                json.put("MAC", macAddress);

                RequestBody body = RequestBody.create(JSON, json.toString());
                Request request = new Request.Builder()
                        .url("https://friday-assistant.ru/logout")
                        .post(body)
                        .build();

                httpClient.newCall(request).execute();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                runOnUiThread(() -> {
                    saveAuthData(null, null);
                    pendingUserLogin = null;
                    currentDialogId = null;
                    userDialogs.clear();
                    chatMessages.clear();
                    if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
                    Toast.makeText(this, "Выход из аккаунта выполнен", Toast.LENGTH_SHORT).show();
                    openMainWindow();
                });
            }
        }).start();
    }

    // --- Multi-Dialogs ---
    private void loadUserDialogs() {
        String token = getSavedToken();
        if (token == null || token.isEmpty()) {
            LinearLayout dialogsLayout = findViewById(R.id.dialogs_layout);
            if (dialogsLayout != null) dialogsLayout.setVisibility(View.GONE);
            return;
        }

        new Thread(() -> {
            try {
                OkHttpClient httpClient = new OkHttpClient();
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");
                JSONObject json = new JSONObject();
                json.put("token", token);

                RequestBody body = RequestBody.create(JSON, json.toString());
                Request request = new Request.Builder()
                        .url("https://friday-assistant.ru/api/get_dialogs")
                        .post(body)
                        .build();

                Response response = httpClient.newCall(request).execute();
                String responseBody = response.body().string();

                runOnUiThread(() -> {
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if ("success".equals(jsonResponse.optString("status"))) {
                            JSONArray dialogsArray = jsonResponse.getJSONArray("dialogs");
                            userDialogs.clear();
                            for (int i = 0; i < dialogsArray.length(); i++) {
                                JSONObject d = dialogsArray.getJSONObject(i);
                                userDialogs.add(new DialogItem(
                                        String.valueOf(d.get("id")),
                                        d.optString("name", "Диалог " + d.get("id"))
                                ));
                            }

                            LinearLayout dialogsLayout = findViewById(R.id.dialogs_layout);
                            if (dialogsLayout != null) dialogsLayout.setVisibility(View.VISIBLE);

                            Spinner dialogSpinner = findViewById(R.id.dialog_spinner);
                            if (dialogSpinner != null) {
                                ArrayAdapter<DialogItem> adapter = new ArrayAdapter<>(
                                        this, android.R.layout.simple_spinner_item, userDialogs
                                );
                                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                                dialogSpinner.setAdapter(adapter);

                                dialogSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                                    @Override
                                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                                        if (position >= 0 && position < userDialogs.size()) {
                                            currentDialogId = userDialogs.get(position).id;
                                            if (currentDialogTitle != null) {
                                                currentDialogTitle.setText(userDialogs.get(position).name);
                                            }
                                            loadDialogHistory(currentDialogId);
                                        }
                                    }

                                    @Override
                                    public void onNothingSelected(AdapterView<?> parent) {}
                                });

                                if (!userDialogs.isEmpty() && currentDialogId == null) {
                                    currentDialogId = userDialogs.get(0).id;
                                    if (currentDialogTitle != null) {
                                        currentDialogTitle.setText(userDialogs.get(0).name);
                                    }
                                }
                            }
                        }
                    } catch (JSONException e) {
                        Log.e("Dialogs", "Error parsing dialogs", e);
                    }
                });
            } catch (Exception e) {
                Log.e("Dialogs", "Error loading dialogs", e);
            }
        }).start();
    }

    private void createNewDialog() {
        new Thread(() -> {
            try {
                String token = getSavedToken();
                if (token == null || token.isEmpty()) return;
                OkHttpClient httpClient = new OkHttpClient();
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");
                JSONObject json = new JSONObject();
                json.put("token", token);
                // Chat name will be dynamically set by facade model on first message!
                json.put("name", "Новый чат");

                RequestBody body = RequestBody.create(JSON, json.toString());
                Request request = new Request.Builder()
                        .url("https://friday-assistant.ru/api/create_dialog")
                        .post(body)
                        .build();

                Response response = httpClient.newCall(request).execute();
                String respStr = response.body().string();
                JSONObject respJson = new JSONObject(respStr);
                if ("success".equals(respJson.optString("status"))) {
                    runOnUiThread(() -> loadUserDialogs());
                }
            } catch (Exception e) {
                Log.e("Dialogs", "Error creating dialog", e);
            }
        }).start();
    }

    private void deleteCurrentDialog() {
        if (currentDialogId == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Удаление чата")
                .setMessage("Вы уверены, что хотите удалить этот чат?")
                .setPositiveButton("Удалить", (dialog, which) -> {
                    new Thread(() -> {
                        try {
                            String token = getSavedToken();
                            OkHttpClient httpClient = new OkHttpClient();
                            MediaType JSON = MediaType.parse("application/json; charset=utf-8");
                            JSONObject json = new JSONObject();
                            json.put("token", token);
                            json.put("dialog_id", currentDialogId);

                            RequestBody body = RequestBody.create(JSON, json.toString());
                            Request request = new Request.Builder()
                                    .url("https://friday-assistant.ru/api/delete_dialog")
                                    .post(body)
                                    .build();

                            Response response = httpClient.newCall(request).execute();
                            String respStr = response.body().string();
                            JSONObject respJson = new JSONObject(respStr);
                            if ("success".equals(respJson.optString("status"))) {
                                currentDialogId = null;
                                runOnUiThread(() -> loadUserDialogs());
                            }
                        } catch (Exception e) {
                            Log.e("Dialogs", "Error deleting dialog", e);
                        }
                    }).start();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void loadDialogHistory(String dialogId) {
        new Thread(() -> {
            try {
                String token = getSavedToken();
                OkHttpClient httpClient = new OkHttpClient();
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");
                JSONObject json = new JSONObject();
                json.put("token", token);
                json.put("dialog_id", dialogId);

                RequestBody body = RequestBody.create(JSON, json.toString());
                Request request = new Request.Builder()
                        .url("https://friday-assistant.ru/api/get_history")
                        .post(body)
                        .build();

                Response response = httpClient.newCall(request).execute();
                String respStr = response.body().string();
                JSONObject respJson = new JSONObject(respStr);
                if ("success".equals(respJson.optString("status")) && respJson.has("history")) {
                    displayHistory(respJson.getJSONArray("history").toString());
                }
            } catch (Exception e) {
                Log.e("DialogHistory", "Error loading history", e);
            }
        }).start();
    }

    public String getUniqueDeviceId(Context context) {
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
            while ((line = bufferedReader.readLine()) != null) jsonString.append(line);
            return new JSONObject(jsonString.toString());
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    public boolean isDataValid(JSONObject deviceData) {
        try {
            return deviceData.has("deviceName") && deviceData.has("password");
        } catch (Exception e) {
            return false;
        }
    }

    public void checkAndCreateSettingsFile() {
        String fileName = "settings.json";
        File file = new File(getFilesDir(), fileName);

        if (!file.exists()) {
            try {
                JSONObject defaultSettings = new JSONObject();
                defaultSettings.put("name", "пятница");
                defaultSettings.put("voice_type", "Aoede");

                FileOutputStream fos = openFileOutput(fileName, Context.MODE_PRIVATE);
                fos.write(defaultSettings.toString().getBytes());
                fos.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
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
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAndRequestPermissions();
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
            }
        }
    }

    @Override
    public void onDestroy() {
        isDestroyed = true;
        stopAudioRecording();
        stopService(new Intent(this, MyForegroundService.class));
        if (webSocket != null) {
            try { webSocket.close(1000, "Activity destroyed"); } catch (Exception e) {}
            webSocket = null;
        }
        if (client != null) {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
            client = null;
        }
        if (botSpeakingReceiver != null) unregisterReceiver(botSpeakingReceiver);
        connectionHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
        instance = null;
    }

    // --- Voice Input with VAD (AudioRecord at 16000Hz 16-bit PCM) ---
    private void toggleAudioRecording() {
        int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
            return;
        }

        if (isRecording) {
            stopAudioRecording();
        } else {
            startAudioRecording();
        }
    }

    private void startAudioRecording() {
        int sampleRate = 16000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR) {
            minBufferSize = sampleRate * 2;
        }

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    minBufferSize * 2
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        minBufferSize * 2
                );
            }

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioRecord", "AudioRecord failed to initialize");
                Toast.makeText(this, "Не удалось инициализировать микрофон", Toast.LENGTH_SHORT).show();
                return;
            }

            audioRecord.startRecording();
            isRecording = true;
            currentStreamId = "stream_" + System.currentTimeMillis();

            // Create new ChatMessage: "Слушаю..."
            currentVoiceUserMessage = ChatMessage.createUser("Слушаю...");
            currentVoiceUserMessage.setUiMsgId(currentStreamId);
            addChatMessage(currentVoiceUserMessage);

            // Update mic button UI state to listening
            if (micButton != null) micButton.setBackgroundResource(R.drawable.mic_button_listening);
            if (micStatusText != null) micStatusText.setText("Слушаю...");

            // Send streaming start command to server
            try {
                JSONObject startCmd = new JSONObject();
                startCmd.put("command", "[Голосовое сообщение]");
                startCmd.put("stream_audio", true);
                startCmd.put("ui_msg_id", currentStreamId);
                startCmd.put("voice_type", getSelectedVoice());
                startCmd.put("mac", getUniqueDeviceId(this));
                if (currentDialogId != null && !currentDialogId.isEmpty()) {
                    startCmd.put("dialog_id", currentDialogId);
                }
                String token = getSavedToken();
                if (token != null && !token.isEmpty()) {
                    startCmd.put("token", token);
                }
                sendEncodedMessage(startCmd);
            } catch (JSONException e) {
                Log.e("AudioStream", "Error sending start stream command", e);
            }

            recordingThread = new Thread(() -> {
                byte[] buffer = new byte[3200]; // 100ms PCM chunks (1600 samples * 2 bytes)
                while (isRecording && audioRecord != null) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        try {
                            String b64 = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP);
                            JSONObject chunk = new JSONObject();
                            chunk.put("type", "audio_stream_chunk");
                            chunk.put("audio_base64", b64);
                            chunk.put("ui_msg_id", currentStreamId);
                            sendEncodedMessage(chunk);
                        } catch (JSONException e) {
                            Log.e("AudioStream", "Error sending audio chunk", e);
                        }
                    }
                }
            });
            recordingThread.start();

        } catch (SecurityException e) {
            Log.e("AudioRecord", "Permission denied", e);
        } catch (Exception e) {
            Log.e("AudioRecord", "Error starting recording", e);
        }
    }

    private void stopAudioRecording() {
        isRecording = false;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e("AudioRecord", "Error stopping audioRecord", e);
            }
            audioRecord = null;
        }

        // Change user message text to "Транскрибирую..."
        if (currentVoiceUserMessage != null) {
            currentVoiceUserMessage.setText("Транскрибирую...");
            if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
        }

        if (micButton != null) micButton.setBackgroundResource(R.drawable.mic_button_idle);
        if (micStatusText != null) micStatusText.setText("Нажмите для записи");

        if (currentStreamId != null) {
            try {
                JSONObject endMsg = new JSONObject();
                endMsg.put("type", "audio_stream_end");
                endMsg.put("ui_msg_id", currentStreamId);
                sendEncodedMessage(endMsg);
            } catch (JSONException e) {
                Log.e("AudioStream", "Error sending stream end", e);
            }
        }
    }

    private void updateVoiceUserMessage(String uiMsgId, String transcribedText) {
        runOnUiThread(() -> {
            if (currentVoiceUserMessage != null && (uiMsgId.isEmpty() || uiMsgId.equals(currentVoiceUserMessage.getUiMsgId()))) {
                currentVoiceUserMessage.setText(transcribedText);
            } else {
                for (ChatMessage msg : chatMessages) {
                    if (uiMsgId.equals(msg.getUiMsgId())) {
                        msg.setText(transcribedText);
                        break;
                    }
                }
            }
            if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
            scrollToBottom();
        });
    }

    private void appendBotResponse(String chunkText) {
        runOnUiThread(() -> {
            if (currentBotResponseMsg == null) {
                currentBotResponseMsg = ChatMessage.createBot(chunkText);
                addChatMessage(currentBotResponseMsg);
            } else {
                currentBotResponseMsg.setText(currentBotResponseMsg.getText() + chunkText);
                if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
                scrollToBottom();
            }
        });
    }

    public void updateResultText(String text) {
        appendBotResponse(text);
    }

    private void scrollToBottom() {
        if (chatRecyclerView != null && chatAdapter != null && chatAdapter.getItemCount() > 0) {
            chatRecyclerView.smoothScrollToPosition(chatAdapter.getItemCount() - 1);
        }
    }
}