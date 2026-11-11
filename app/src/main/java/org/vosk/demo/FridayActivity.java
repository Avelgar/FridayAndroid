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
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class FridayActivity extends Activity {

    // Video streaming
    public static final int VIDEO_MODE_NONE = 0;
    public static final int VIDEO_MODE_FRONT = 1;
    public static final int VIDEO_MODE_BACK = 2;
    public static final int VIDEO_MODE_SCREEN = 3;

    private static final int PERMISSIONS_REQUEST_CAMERA = 2001;
    private static final int REQUEST_SCREEN_CAPTURE = 2002;

    private int currentVideoMode = VIDEO_MODE_NONE;
    private long lastVideoFrameTime = 0;
    private final ExecutorService videoExecutor = Executors.newSingleThreadExecutor();

    private final Handler videoTimerHandler = new Handler(Looper.getMainLooper());
    private Runnable videoTimerRunnable;

    private TextView tvVideoStatus;
    private View videoPreviewContainer;
    private TextureView cameraTextureView;
    private Button btnVideoFront;
    private Button btnVideoBack;
    private Button btnVideoScreen;

    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private HandlerThread cameraBackgroundThread;
    private Handler cameraBackgroundHandler;

    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReaderScreen;
    private Intent screenCaptureIntentData;
    private int screenCaptureResultCode = 0;

    private static FridayActivity instance;
    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;
    private static final int PICK_IMAGE_REQUEST = 1001;

    private WebSocket webSocket;

    private LinearLayout mainContent;
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

    // --- Audio Streaming with continuous VAD ---
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private boolean isMicModeActive = false;
    private String vadState = "idle";
    private Thread recordingThread = null;
    private String currentStreamId = null;
    private ChatMessage currentVoiceUserMessage = null;
    private ChatMessage currentBotResponseMsg = null;
    private long lastBotSpeakingTime = 0;

    // VAD constants (16kHz 16-bit mono PCM: 1 sec = 16000 samples)
    private static final double VAD_THRESHOLD = 800.0;
    private static final int SILENCE_FRAMES_LIMIT = 24000; // 1.5 seconds silence
    private static final int PRE_BUFFER_LIMIT = 8000; // 0.5s pre-buffer
    private final List<Short> preBuffer = new ArrayList<>();
    private final List<Short> pcmBuffer = new ArrayList<>();
    private int silenceSamples = 0;

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

        // 1. ПРОВЕРКА АВТОРИЗАЦИИ: если токена нет, сразу перенаправляем на вход
        String token = getSavedToken();
        if (token == null || token.trim().isEmpty()) {
            Intent loginIntent = new Intent(this, LoginActivity.class);
            startActivity(loginIntent);
            finish();
            return;
        }

        instance = this;
        checkAndCreateSettingsFile();

        JSONObject settings = readDataFromFile(this, settingsFileName);
        try {
            if (settings != null && settings.has("name")) {
                botName = settings.getString("name").toLowerCase();
            }
            if (settings != null && settings.has("user_login")) {
                pendingUserLogin = settings.getString("user_login");
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        initWebSocketConnection();
        startConnectionChecker();

        botSpeakingReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (MyForegroundService.ACTION_BOT_SPEAKING.equals(intent.getAction())) {
                    isBotSpeaking = intent.getBooleanExtra(MyForegroundService.EXTRA_IS_SPEAKING, false);
                    if (!isBotSpeaking) {
                        lastBotSpeakingTime = System.currentTimeMillis();
                        if (isMicModeActive && "processing".equals(vadState)) {
                            vadState = "idle";
                            runOnUiThread(() -> {
                                if (micStatusText != null) micStatusText.setText("Слушаю...");
                            });
                        }
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(MyForegroundService.ACTION_BOT_SPEAKING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(botSpeakingReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(botSpeakingReceiver, filter);
        }

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
                    String token = getSavedToken();
                    if (token != null && !token.isEmpty()) {
                        sendAuthMessage(token);
                    }
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

    public void sendAuthMessage(String token) {
        if (webSocket != null && token != null && !token.isEmpty()) {
            try {
                JSONObject auth = new JSONObject();
                auth.put("type", "auth");
                auth.put("token", token);
                auth.put("device_name", getDeviceName());
                auth.put("device_type", "phone");
                sendEncodedMessage(auth);
            } catch (JSONException e) {
                Log.e("WebSocket", "Error sending auth message", e);
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

                if ("msg_id_map".equals(msgType)) {
                    String targetUiId = jsonMessage.optString("ui_msg_id", "");
                    String userMsgId = jsonMessage.optString("user_msg_id", "");
                    if (!targetUiId.isEmpty() && !userMsgId.isEmpty()) {
                        for (ChatMessage m : chatMessages) {
                            if (targetUiId.equals(m.getUiMsgId())) {
                                m.setId(userMsgId);
                                break;
                            }
                        }
                    }
                    return;
                }

                if ("text_chunk".equals(msgType) || "bot_chunk".equals(msgType)) {
                    String chunkText = jsonMessage.optString("text", "");
                    String chunkMsgId = jsonMessage.optString("message_id", "");
                    String chunkUiId = jsonMessage.optString("ui_msg_id", "");
                    if (!chunkText.isEmpty()) {
                        appendBotChunk(chunkText, chunkMsgId, chunkUiId);
                    }
                    return;
                }

                if ("transcription".equals(msgType) || "recognized_text".equals(msgType) || "user_transcription".equals(msgType)) {
                    String transcribedText = jsonMessage.optString("text", "");
                    String targetUiId = jsonMessage.optString("ui_msg_id", "");
                    updateVoiceUserMessage(targetUiId, transcribedText);
                    return;
                }

                if ("dialog_deleted".equals(msgType)) {
                    String deletedId = jsonMessage.optString("dialog_id", "");
                    if (!deletedId.isEmpty() && deletedId.equals(currentDialogId)) {
                        currentDialogId = null;
                        chatMessages.clear();
                        if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
                    }
                    loadUserDialogs();
                    return;
                }

                if ("dialog_renamed".equals(msgType) || "dialog_created".equals(msgType)) {
                    loadUserDialogs();
                    return;
                }

                if ("delete_message".equals(msgType)) {
                    String delId = jsonMessage.optString("message_id", jsonMessage.optString("ui_msg_id", ""));
                    if (!delId.isEmpty()) {
                        for (int i = 0; i < chatMessages.size(); i++) {
                            ChatMessage m = chatMessages.get(i);
                            if (delId.equals(m.getId()) || delId.equals(m.getUiMsgId())) {
                                chatMessages.remove(i);
                                if (chatAdapter != null) chatAdapter.notifyItemRemoved(i);
                                break;
                            }
                        }
                    }
                    if (isMicModeActive) {
                        vadState = "idle";
                    }
                    return;
                }

                if ("new_message".equals(msgType)) {
                    String msgDialogId = jsonMessage.optString("dialog_id", "");
                    if (!msgDialogId.isEmpty() && currentDialogId != null && !msgDialogId.equals(currentDialogId)) {
                        return;
                    }
                    if (!msgDialogId.isEmpty() && currentDialogId == null) {
                        currentDialogId = msgDialogId;
                    }
                    String sender = jsonMessage.optString("sender", "Бот");
                    String text = jsonMessage.optString("text", "");
                    String targetUiId = jsonMessage.optString("ui_msg_id", "");
                    String msgId = jsonMessage.optString("message_id", "");

                    boolean isFinal = text.isEmpty() && (!jsonMessage.has("actions") || jsonMessage.optJSONArray("actions") == null || jsonMessage.optJSONArray("actions").length() == 0);
                    if (isFinal) {
                        currentBotResponseMsg = null;
                        if (isMicModeActive) {
                            vadState = "idle";
                        }
                        return;
                    }

                    if (!text.isEmpty()) {
                        if ("Вы".equalsIgnoreCase(sender) || "user".equalsIgnoreCase(sender)) {
                            boolean updated = false;
                            for (ChatMessage m : chatMessages) {
                                if (m.getType() == ChatMessage.TYPE_USER && (
                                        (!targetUiId.isEmpty() && targetUiId.equals(m.getUiMsgId())) ||
                                        (!msgId.isEmpty() && msgId.equals(m.getId())) ||
                                        (!msgId.isEmpty() && msgId.equals(m.getUiMsgId())) ||
                                        (m.getText() != null && (m.getText().contains("[Аудиосообщение]") || m.getText().contains("🎤") || m.getText().contains("⏳")))
                                )) {
                                    m.setText(text);
                                    if (!msgId.isEmpty()) m.setId(msgId);
                                    updated = true;
                                    break;
                                }
                            }
                            if (!updated) {
                                ChatMessage userMsg = ChatMessage.createUser(text);
                                userMsg.setId(msgId);
                                userMsg.setUiMsgId(targetUiId);
                                addChatMessage(userMsg);
                            } else if (chatAdapter != null) {
                                chatAdapter.notifyDataSetChanged();
                                scrollToBottom();
                            }
                        } else if ("Действие".equalsIgnoreCase(sender)) {
                            currentBotResponseMsg = null;
                            ChatMessage actMsg = ChatMessage.createBot(text);
                            actMsg.setId(msgId);
                            addChatMessage(actMsg);
                        } else {
                            // Сообщение бота: ищем по ID или добавляем/дополняем
                            appendBotChunk(text, msgId, targetUiId);
                        }
                    }
                    executeInBackground(jsonMessage);
                    return;
                }

                if ("clear_history".equals(msgType) || "delete_history".equals(msgType)) {
                    chatMessages.clear();
                    if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
                    return;
                }

                // Сессия истекла или токен недействителен -> перенаправляем на экран входа
                if ("auth_error".equals(msgType) || encodedMessage.contains("Недействительный токен")) {
                    saveAuthData(null, null);
                    pendingUserLogin = null;
                    Toast.makeText(this, "Сессия истекла. Пожалуйста, выполните вход.", Toast.LENGTH_SHORT).show();

                    Intent intent = new Intent(FridayActivity.this, LoginActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                    return;
                }

                if ("account_sync_success".equals(msgType) || encodedMessage.contains("Данные успешно обработаны") || (jsonMessage.has("user_login") && !jsonMessage.isNull("user_login"))) {
                    try {
                        String userLogin = null;
                        if (jsonMessage.has("user_login") && !jsonMessage.isNull("user_login")) {
                            userLogin = jsonMessage.getString("user_login");
                        } else if (jsonMessage.has("login") && !jsonMessage.isNull("login")) {
                            userLogin = jsonMessage.getString("login");
                        }

                        String userToken = null;
                        if (jsonMessage.has("token") && !jsonMessage.isNull("token")) {
                            userToken = jsonMessage.getString("token");
                        } else if (jsonMessage.has("user_token") && !jsonMessage.isNull("user_token")) {
                            userToken = jsonMessage.getString("user_token");
                        }

                        if (userLogin != null && !userLogin.trim().isEmpty()) {
                            pendingUserLogin = userLogin.trim();
                            if (userToken == null || userToken.trim().isEmpty()) {
                                userToken = getSavedToken();
                            }
                            saveAuthData(userToken, pendingUserLogin);
                            updateUserUI();
                            if (userToken != null && !userToken.trim().isEmpty()) {
                                loadUserDialogs();
                            }
                            if (jsonMessage.has("history")) {
                                pendingHistory = jsonMessage.getJSONArray("history").toString();
                                if (mainContent != null && mainContent.getVisibility() == View.VISIBLE) {
                                    displayHistory(pendingHistory);
                                }
                            }
                        } else {
                            updateUserUI();
                        }
                    } catch (JSONException e) {
                        Log.e("SyncHandler", "Error processing sync data", e);
                    }
                } else {
                    String respText = jsonMessage.optString("response", jsonMessage.optString("text", ""));
                    if (!respText.isEmpty()) {
                        appendBotChunk(respText, "", "");
                    }
                }
            } catch (JSONException e) {
                Log.e("MessageHandler", "Error parsing message", e);
            }
        });
    }

    @SuppressLint("WrongViewCast")
    private void openMainWindow() {
        setContentView(R.layout.main);

        mainContent = findViewById(R.id.main_content);
        settingsContent = findViewById(R.id.settings_content);

        // Header dialog controls
        ImageButton btnNewDialog = findViewById(R.id.btn_new_dialog);
        ImageButton btnDeleteDialog = findViewById(R.id.btn_delete_dialog);
        if (btnNewDialog != null) btnNewDialog.setOnClickListener(v -> createNewDialog());
        if (btnDeleteDialog != null) btnDeleteDialog.setOnClickListener(v -> deleteCurrentDialog());

        // Nav bar buttons
        View navMain = findViewById(R.id.nav_main);
        View navSettings = findViewById(R.id.nav_settings);

        if (navMain != null) navMain.setOnClickListener(v -> showMainContent());
        if (navSettings != null) navSettings.setOnClickListener(v -> showSettingsContent());

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

        tvVideoStatus = findViewById(R.id.tv_video_status);
        videoPreviewContainer = findViewById(R.id.video_preview_container);
        cameraTextureView = findViewById(R.id.camera_texture_view);
        btnVideoFront = findViewById(R.id.btn_video_front);
        btnVideoBack = findViewById(R.id.btn_video_back);
        btnVideoScreen = findViewById(R.id.btn_video_screen);

        if (btnVideoFront != null) btnVideoFront.setOnClickListener(v -> toggleVideoMode(VIDEO_MODE_FRONT));
        if (btnVideoBack != null) btnVideoBack.setOnClickListener(v -> toggleVideoMode(VIDEO_MODE_BACK));
        if (btnVideoScreen != null) btnVideoScreen.setOnClickListener(v -> toggleVideoMode(VIDEO_MODE_SCREEN));

        showMainContent();

        if (!isMyServiceRunning(MyForegroundService.class)) {
            Intent serviceIntent = new Intent(this, MyForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }

        String token = getSavedToken();
        if (token != null && !token.isEmpty()) {
            if (pendingHistory != null) {
                displayHistory(pendingHistory);
                pendingHistory = null;
            }
            loadUserDialogs();
        }
    }

    private void updateUserUI() {
        Button btnUserProfile = findViewById(R.id.btn_user_profile);
        LinearLayout dialogsLayout = findViewById(R.id.dialogs_layout);

        String savedLogin = pendingUserLogin;
        if (savedLogin == null || savedLogin.isEmpty()) {
            JSONObject settings = readDataFromFile(this, settingsFileName);
            if (settings != null && settings.has("user_login")) {
                savedLogin = settings.optString("user_login");
            }
        }
        if (savedLogin == null || savedLogin.isEmpty()) {
            savedLogin = "Профиль";
        }

        ImageButton btnClearHistory = findViewById(R.id.btn_clear_history);
        if (btnClearHistory != null) btnClearHistory.setVisibility(View.GONE);

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
        if (dialogsLayout != null) dialogsLayout.setVisibility(View.VISIBLE);
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
        } else if (requestCode == REQUEST_SCREEN_CAPTURE && resultCode == RESULT_OK) {
            screenCaptureResultCode = resultCode;
            screenCaptureIntentData = data;
            initVirtualDisplay();
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

    public String getDeviceName() {
        JSONObject settings = readDataFromFile(this, settingsFileName);
        if (settings != null && settings.has("device_name")) {
            String customName = settings.optString("device_name").trim();
            if (!customName.isEmpty()) return customName;
        }
        String model = Build.MODEL;
        if (model == null || model.trim().isEmpty()) {
            return "Android Phone";
        }
        return model;
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
            String localUiId = "msg_" + System.currentTimeMillis();
            JSONObject messageJson = new JSONObject();
            messageJson.put("command", message);
            messageJson.put("name", botName);
            messageJson.put("type", "текстовое сообщение");
            messageJson.put("voice_type", getSelectedVoice());
            messageJson.put("device_name", getDeviceName());
            messageJson.put("device_type", "phone");
            messageJson.put("ui_msg_id", localUiId);

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

            ChatMessage userMsg = ChatMessage.createUser(message.isEmpty() ? "[Фото]" : message);
            userMsg.setUiMsgId(localUiId);
            addChatMessage(userMsg);

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
                currentBotResponseMsg = null;

                for (int i = 0; i < historyArray.length(); i++) {
                    JSONObject message = historyArray.getJSONObject(i);
                    String sender = message.optString("sender", "");
                    String text = message.optString("text", "");
                    String msgId = message.optString("id", "");

                    if (sender.isEmpty() || text.isEmpty()) continue;

                    boolean isUser = sender.equalsIgnoreCase("user") || sender.equalsIgnoreCase("пользователь") || sender.equalsIgnoreCase("вы");
                    if (isUser) {
                        ChatMessage uMsg = ChatMessage.createUser(text);
                        uMsg.setId(msgId);
                        chatMessages.add(uMsg);
                    } else {
                        String cleanBotText = text.replaceAll("(?i)(^|\\n)(бот|bot|пятница):\\s*", "$1").trim();
                        ChatMessage bMsg = ChatMessage.createBot(cleanBotText);
                        bMsg.setId(msgId);
                        chatMessages.add(bMsg);
                    }
                }
                if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
                scrollToBottom();
            } catch (JSONException e) {
                Log.e("History", "Error parsing history", e);
            }
        });
    }

    private void updateNavSelection(int selectedTab) {
        ImageView mainIcon = findViewById(R.id.main_btn_icon);
        TextView mainText = findViewById(R.id.main_btn);
        ImageView settingsIcon = findViewById(R.id.settings_btn_icon);
        TextView settingsText = findViewById(R.id.settings_btn);

        int selectedColor = ContextCompat.getColor(this, R.color.nav_selected);
        int unselectedColor = ContextCompat.getColor(this, R.color.nav_unselected);

        if (mainIcon != null) mainIcon.setColorFilter(selectedTab == 0 ? selectedColor : unselectedColor);
        if (mainText != null) mainText.setTextColor(selectedTab == 0 ? selectedColor : unselectedColor);

        if (settingsIcon != null) settingsIcon.setColorFilter(selectedTab == 1 ? selectedColor : unselectedColor);
        if (settingsText != null) settingsText.setTextColor(selectedTab == 1 ? selectedColor : unselectedColor);
    }

    private void showMainContent() {
        if (mainContent != null) mainContent.setVisibility(View.VISIBLE);
        if (settingsContent != null) settingsContent.setVisibility(View.GONE);
        updateNavSelection(0);
    }

    private void showSettingsContent() {
        if (mainContent != null) mainContent.setVisibility(View.GONE);
        if (settingsContent != null) settingsContent.setVisibility(View.VISIBLE);
        updateNavSelection(1);

        Spinner voiceSpinner = findViewById(R.id.bot_voice_spinner);
        EditText etDeviceName = findViewById(R.id.et_device_name);
        Button saveBtn = findViewById(R.id.save_bot_voice_btn);

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

        if (etDeviceName != null) {
            etDeviceName.setText(getDeviceName());
        }

        if (saveBtn != null) {
            saveBtn.setOnClickListener(v -> {
                try {
                    JSONObject settings = readDataFromFile(this, settingsFileName);
                    if (settings == null) settings = new JSONObject();

                    if (voiceSpinner != null) {
                        int pos = voiceSpinner.getSelectedItemPosition();
                        if (pos >= 0 && pos < voiceKeys.length) {
                            settings.put("voice_type", voiceKeys[pos]);
                        }
                    }

                    if (etDeviceName != null) {
                        String newName = etDeviceName.getText().toString().trim();
                        if (!newName.isEmpty()) {
                            settings.put("device_name", newName);
                        } else {
                            settings.remove("device_name");
                        }
                    }

                    saveDataToFile(this, settingsFileName, settings);
                    Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();

                    String token = getSavedToken();
                    if (token != null && !token.isEmpty()) {
                        sendAuthMessage(token);
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                    Toast.makeText(this, "Ошибка сохранения настроек", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void logoutUser() {
        new Thread(() -> {
            try {
                OkHttpClient httpClient = new OkHttpClient();
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");
                JSONObject json = new JSONObject();
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

                    if (webSocket != null) {
                        try { webSocket.close(1000, "Logout"); } catch (Exception e) {}
                        webSocket = null;
                    }

                    stopService(new Intent(this, MyForegroundService.class));

                    Intent intent = new Intent(FridayActivity.this, LoginActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                });
            }
        }).start();
    }

    // --- Multi-Dialogs ---
    private void loadUserDialogs() {
        String token = getSavedToken();
        if (token == null || token.isEmpty()) {
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

                            boolean hasChats = !userDialogs.isEmpty();
                            if (!hasChats) {
                                currentDialogId = null;
                                chatMessages.clear();
                                if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
                                userDialogs.add(new DialogItem("", "Нет диалогов"));
                            }

                            LinearLayout dialogsLayout = findViewById(R.id.dialogs_layout);
                            if (dialogsLayout != null) dialogsLayout.setVisibility(View.VISIBLE);

                            Spinner dialogSpinner = findViewById(R.id.dialog_spinner);
                            if (dialogSpinner != null) {
                                // 1. Проверяем, существует ли все еще выбранный диалог в новом списке
                                int targetPos = -1;
                                if (currentDialogId != null) {
                                    for (int i = 0; i < userDialogs.size(); i++) {
                                        if (userDialogs.get(i).id.equals(currentDialogId)) {
                                            targetPos = i;
                                            break;
                                        }
                                    }
                                }

                                // 2. Отключаем слушатель перед установкой нового адаптера (чтобы не триггерился onItemSelected 0)
                                dialogSpinner.setOnItemSelectedListener(null);

                                ArrayAdapter<DialogItem> adapter = new ArrayAdapter<>(
                                        this, android.R.layout.simple_spinner_item, userDialogs
                                );
                                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                                dialogSpinner.setAdapter(adapter);

                                if (targetPos >= 0) {
                                    dialogSpinner.setSelection(targetPos, false);
                                    if (currentDialogTitle != null) {
                                        currentDialogTitle.setText(userDialogs.get(targetPos).name);
                                    }
                                } else if (hasChats && currentDialogId == null) {
                                    currentDialogId = userDialogs.get(0).id;
                                    dialogSpinner.setSelection(0, false);
                                    if (currentDialogTitle != null) {
                                        currentDialogTitle.setText(userDialogs.get(0).name);
                                    }
                                    loadDialogHistory(currentDialogId);
                                } else if (!hasChats) {
                                    dialogSpinner.setSelection(0, false);
                                    if (currentDialogTitle != null) currentDialogTitle.setText("Нет диалогов");
                                }

                                // 3. Вешаем слушатель обратно
                                dialogSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                                    @Override
                                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                                        if (position >= 0 && position < userDialogs.size()) {
                                            String selectedId = userDialogs.get(position).id;
                                            if (selectedId != null && !selectedId.isEmpty() && !selectedId.equals(currentDialogId)) {
                                                currentDialogId = selectedId;
                                                if (currentDialogTitle != null) {
                                                    currentDialogTitle.setText(userDialogs.get(position).name);
                                                }
                                                loadDialogHistory(currentDialogId);
                                            }
                                        }
                                    }

                                    @Override
                                    public void onNothingSelected(AdapterView<?> parent) {}
                                });
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
                    String newId = respJson.optString("dialog_id", "");
                    runOnUiThread(() -> {
                        if (!newId.isEmpty()) {
                            currentDialogId = newId;
                            chatMessages.clear();
                            if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
                        }
                        loadUserDialogs();
                    });
                }
            } catch (Exception e) {
                Log.e("Dialogs", "Error creating dialog", e);
            }
        }).start();
    }

    private void deleteCurrentDialog() {
        if (currentDialogId == null || currentDialogId.trim().isEmpty()) return;
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
                                runOnUiThread(() -> {
                                    currentDialogId = null;
                                    chatMessages.clear();
                                    currentBotResponseMsg = null;
                                    if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
                                    loadUserDialogs();
                                });
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
        if (currentVideoMode == VIDEO_MODE_FRONT || currentVideoMode == VIDEO_MODE_BACK) {
            startCameraPreview(currentVideoMode);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (currentVideoMode == VIDEO_MODE_FRONT || currentVideoMode == VIDEO_MODE_BACK) {
            stopCamera();
        }
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> permissions = new ArrayList<>();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.RECORD_AUDIO);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.POST_NOTIFICATIONS);
                }
            }
            if (!permissions.isEmpty()) {
                ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSIONS_REQUEST_RECORD_AUDIO);
            }
        }
    }

    @Override
    public void onDestroy() {
        isDestroyed = true;
        stopAudioRecording();
        stopCamera();
        stopScreenCapture();
        if (videoExecutor != null) {
            videoExecutor.shutdown();
        }
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

    // --- Voice Input with continuous VAD (matching Web client) ---
    private void toggleAudioRecording() {
        int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
            return;
        }

        if (isMicModeActive) {
            isMicModeActive = false;
            stopAudioRecording();
        } else {
            isMicModeActive = true;
            vadState = "idle";
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
                isMicModeActive = false;
                return;
            }

            audioRecord.startRecording();
            isRecording = true;
            vadState = "idle";
            synchronized (preBuffer) { preBuffer.clear(); }
            synchronized (pcmBuffer) { pcmBuffer.clear(); }
            silenceSamples = 0;

            runOnUiThread(() -> {
                if (micButton != null) micButton.setBackgroundResource(R.drawable.mic_button_listening);
                if (micStatusText != null) {
                    micStatusText.setVisibility(View.VISIBLE);
                    micStatusText.setText("Слушаю...");
                }
            });

            recordingThread = new Thread(() -> {
                short[] sampleBuffer = new short[1600]; // 100ms at 16kHz
                while (isRecording && audioRecord != null) {
                    int read = audioRecord.read(sampleBuffer, 0, sampleBuffer.length);
                    if (read <= 0) continue;

                    if (isBotSpeaking || (System.currentTimeMillis() - lastBotSpeakingTime < 600)) {
                        silenceSamples = 0;
                        continue;
                    }

                    if ("processing".equals(vadState)) {
                        continue;
                    }

                    long sum = 0;
                    for (int i = 0; i < read; i++) {
                        sum += Math.abs((int) sampleBuffer[i]);
                    }
                    double volume = (double) sum / read;

                    if ("idle".equals(vadState)) {
                        synchronized (preBuffer) {
                            for (int i = 0; i < read; i++) {
                                preBuffer.add(sampleBuffer[i]);
                            }
                            while (preBuffer.size() > PRE_BUFFER_LIMIT) {
                                preBuffer.remove(0);
                            }
                        }

                        if (volume > VAD_THRESHOLD) {
                            vadState = "recording";
                            silenceSamples = 0;
                            currentStreamId = "stream_" + System.currentTimeMillis();

                            synchronized (pcmBuffer) {
                                pcmBuffer.clear();
                                synchronized (preBuffer) {
                                    pcmBuffer.addAll(preBuffer);
                                    preBuffer.clear();
                                }
                            }

                            runOnUiThread(() -> {
                                currentVoiceUserMessage = ChatMessage.createUser("🎤 [Слушаю...]");
                                currentVoiceUserMessage.setUiMsgId(currentStreamId);
                                addChatMessage(currentVoiceUserMessage);
                                currentBotResponseMsg = null;
                            });

                            try {
                                JSONObject startCmd = new JSONObject();
                                startCmd.put("command", "");
                                startCmd.put("stream_audio", true);
                                startCmd.put("ui_msg_id", currentStreamId);
                                startCmd.put("voice_type", getSelectedVoice());
                                startCmd.put("device_name", getDeviceName());
                                startCmd.put("device_type", "phone");
                                if (currentDialogId != null && !currentDialogId.isEmpty()) {
                                    startCmd.put("dialog_id", currentDialogId);
                                }
                                String token = getSavedToken();
                                if (token != null && !token.isEmpty()) {
                                    startCmd.put("token", token);
                                }
                                sendEncodedMessage(startCmd);
                            } catch (JSONException e) {
                                Log.e("AudioStream", "Error sending start command", e);
                            }

                            sendShortBufferChunk();

                            if (currentVideoMode != VIDEO_MODE_NONE) {
                                startVideoStreamingTimer(currentStreamId);
                            }

                        }
                    } else if ("recording".equals(vadState)) {
                        synchronized (pcmBuffer) {
                            for (int i = 0; i < read; i++) {
                                pcmBuffer.add(sampleBuffer[i]);
                            }
                        }
                        sendShortBufferChunk();

                        if ("recording".equals(vadState) && currentStreamId != null && currentVideoMode != VIDEO_MODE_NONE) {
                            long now = System.currentTimeMillis();
                            if (now - lastVideoFrameTime >= 1000) {
                                lastVideoFrameTime = now;
                                final String sId = currentStreamId;
                                captureAndSendVideoFrame(sId);
                            }
                        }

                        if (volume < VAD_THRESHOLD) {
                            silenceSamples += read;
                            if (silenceSamples > SILENCE_FRAMES_LIMIT) {
                                vadState = "processing";
                                silenceSamples = 0;
                                stopVideoStreamingTimer();
                                sendShortBufferChunk();

                                runOnUiThread(() -> {
                                    if (currentVoiceUserMessage != null) {
                                        currentVoiceUserMessage.setText("⏳ Транскрибирую...");
                                        if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
                                    }
                                });

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
                        } else {
                            silenceSamples = 0;
                        }
                    }
                }
            });
            recordingThread.start();

        } catch (SecurityException e) {
            Log.e("AudioRecord", "Permission denied", e);
            isMicModeActive = false;
        } catch (Exception e) {
            Log.e("AudioRecord", "Error starting recording", e);
            isMicModeActive = false;
        }
    }

    private void sendShortBufferChunk() {
        byte[] bytesToSend;
        synchronized (pcmBuffer) {
            if (pcmBuffer.isEmpty() || currentStreamId == null) return;
            bytesToSend = new byte[pcmBuffer.size() * 2];
            for (int i = 0; i < pcmBuffer.size(); i++) {
                short val = pcmBuffer.get(i);
                bytesToSend[i * 2] = (byte) (val & 0xFF);
                bytesToSend[i * 2 + 1] = (byte) ((val >> 8) & 0xFF);
            }
            pcmBuffer.clear();
        }

        try {
            String b64 = Base64.encodeToString(bytesToSend, Base64.NO_WRAP);
            JSONObject chunk = new JSONObject();
            chunk.put("type", "audio_stream_chunk");
            chunk.put("audio_base64", b64);
            chunk.put("ui_msg_id", currentStreamId);
            sendEncodedMessage(chunk);
        } catch (JSONException e) {
            Log.e("AudioStream", "Error sending audio chunk", e);
        }
    }

    private void stopAudioRecording() {
        stopVideoStreamingTimer();
        isRecording = false;
        vadState = "idle";
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e("AudioRecord", "Error stopping audioRecord", e);
            }
            audioRecord = null;
        }

        if (currentStreamId != null) {
            try {
                JSONObject endMsg = new JSONObject();
                endMsg.put("type", "audio_stream_end");
                endMsg.put("ui_msg_id", currentStreamId);
                sendEncodedMessage(endMsg);
            } catch (JSONException e) {
                Log.e("AudioStream", "Error sending stream end", e);
            }
            currentStreamId = null;
        }

        runOnUiThread(() -> {
            if (micButton != null) micButton.setBackgroundResource(R.drawable.mic_button_idle);
            if (micStatusText != null) {
                micStatusText.setVisibility(View.GONE);
                micStatusText.setText(R.string.mic_idle);
            }
        });
    }

    private void updateVoiceUserMessage(String uiMsgId, String transcribedText) {
        if (transcribedText == null || transcribedText.trim().isEmpty()) return;
        runOnUiThread(() -> {
            boolean updated = false;
            if (uiMsgId != null && !uiMsgId.isEmpty()) {
                for (ChatMessage msg : chatMessages) {
                    if (msg.getType() == ChatMessage.TYPE_USER && (uiMsgId.equals(msg.getUiMsgId()) || uiMsgId.equals(msg.getId()))) {
                        msg.setText(transcribedText);
                        updated = true;
                        break;
                    }
                }
            }
            if (!updated && currentVoiceUserMessage != null) {
                currentVoiceUserMessage.setText(transcribedText);
                updated = true;
            }
            if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
            scrollToBottom();
        });
    }

    private void appendBotChunk(String chunkText, String msgId, String uiMsgId) {
        if (chunkText == null || chunkText.isEmpty()) return;

        String cleaned = chunkText.replaceAll("(?i)(^|\\n)(бот|bot|пятница):\\s*", "$1").trim();
        if (cleaned.isEmpty()) return;

        runOnUiThread(() -> {
            // 1. Ищем существующее сообщение бота по msgId или uiMsgId
            ChatMessage targetBotMsg = null;
            if (msgId != null && !msgId.isEmpty()) {
                for (ChatMessage m : chatMessages) {
                    if (m.getType() == ChatMessage.TYPE_BOT && msgId.equals(m.getId())) {
                        targetBotMsg = m;
                        break;
                    }
                }
            }
            if (targetBotMsg == null && uiMsgId != null && !uiMsgId.isEmpty()) {
                for (ChatMessage m : chatMessages) {
                    if (m.getType() == ChatMessage.TYPE_BOT && uiMsgId.equals(m.getUiMsgId())) {
                        targetBotMsg = m;
                        break;
                    }
                }
            }
            if (targetBotMsg == null && currentBotResponseMsg != null) {
                targetBotMsg = currentBotResponseMsg;
            }

            if (targetBotMsg == null) {
                targetBotMsg = ChatMessage.createBot(cleaned);
                if (msgId != null && !msgId.isEmpty()) targetBotMsg.setId(msgId);
                if (uiMsgId != null && !uiMsgId.isEmpty()) targetBotMsg.setUiMsgId(uiMsgId);
                currentBotResponseMsg = targetBotMsg;
                addChatMessage(targetBotMsg);
            } else {
                if (msgId != null && !msgId.isEmpty() && targetBotMsg.getId() == null) {
                    targetBotMsg.setId(msgId);
                }
                String existing = targetBotMsg.getText();
                if (existing != null && !existing.isEmpty()) {
                    if (!existing.endsWith(" ") && !existing.endsWith("\n") && !cleaned.startsWith(" ") && !cleaned.startsWith("\n")) {
                        targetBotMsg.setText(existing + " " + cleaned);
                    } else {
                        targetBotMsg.setText(existing + cleaned);
                    }
                } else {
                    targetBotMsg.setText(cleaned);
                }
                currentBotResponseMsg = targetBotMsg;
                if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
                scrollToBottom();
            }
        });
    }

    public void resetCurrentBotResponse() {
        currentBotResponseMsg = null;
    }

    public void updateResultText(String text) {
        appendBotChunk(text, "", "");
    }

    public void clearGuestHistoryLocally() {
        runOnUiThread(() -> {
            chatMessages.clear();
            currentBotResponseMsg = null;
            pendingHistory = null;
            if (chatAdapter != null) {
                chatAdapter.notifyDataSetChanged();
            }
            Toast.makeText(this, "История очищена", Toast.LENGTH_SHORT).show();
        });
    }

    private void scrollToBottom() {
        if (chatRecyclerView != null && chatAdapter != null && chatAdapter.getItemCount() > 0) {
            chatRecyclerView.smoothScrollToPosition(chatAdapter.getItemCount() - 1);
        }
    }

    // ==========================================
    // VIDEO STREAMING (Camera / Screen Capture)
    // ==========================================

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (currentVideoMode == VIDEO_MODE_FRONT || currentVideoMode == VIDEO_MODE_BACK) {
                    startCameraPreview(currentVideoMode);
                }
            } else {
                Toast.makeText(this, "Доступ к камере отклонен", Toast.LENGTH_SHORT).show();
                setVideoMode(VIDEO_MODE_NONE);
            }
        } else if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                isMicModeActive = true;
                vadState = "idle";
                startAudioRecording();
            } else {
                Toast.makeText(this, "Доступ к микрофону отклонен", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void toggleVideoMode(int mode) {
        if (currentVideoMode == mode) {
            setVideoMode(VIDEO_MODE_NONE);
        } else {
            setVideoMode(mode);
        }
    }

    private synchronized void startVideoStreamingTimer(final String streamId) {
        stopVideoStreamingTimer();
        if (streamId == null || currentVideoMode == VIDEO_MODE_NONE) return;

        lastVideoFrameTime = System.currentTimeMillis();
        captureAndSendVideoFrame(streamId);

        videoTimerRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentStreamId != null && currentStreamId.equals(streamId) && currentVideoMode != VIDEO_MODE_NONE) {
                    lastVideoFrameTime = System.currentTimeMillis();
                    captureAndSendVideoFrame(streamId);
                    videoTimerHandler.postDelayed(this, 1000);
                }
            }
        };
        videoTimerHandler.postDelayed(videoTimerRunnable, 1000);
    }

    private synchronized void stopVideoStreamingTimer() {
        if (videoTimerRunnable != null) {
            videoTimerHandler.removeCallbacks(videoTimerRunnable);
            videoTimerRunnable = null;
        }
    }

    private void setVideoMode(int mode) {
        currentVideoMode = mode;

        runOnUiThread(() -> {
            int normalColor = ContextCompat.getColor(this, R.color.text_secondary);
            int activeColor = ContextCompat.getColor(this, R.color.brand_blue);

            if (btnVideoFront != null) btnVideoFront.setTextColor(mode == VIDEO_MODE_FRONT ? activeColor : normalColor);
            if (btnVideoBack != null) btnVideoBack.setTextColor(mode == VIDEO_MODE_BACK ? activeColor : normalColor);
            if (btnVideoScreen != null) btnVideoScreen.setTextColor(mode == VIDEO_MODE_SCREEN ? activeColor : normalColor);

            if (videoPreviewContainer != null) {
                videoPreviewContainer.setVisibility((mode == VIDEO_MODE_FRONT || mode == VIDEO_MODE_BACK) ? View.VISIBLE : View.GONE);
            }

            if (tvVideoStatus != null) {
                switch (mode) {
                    case VIDEO_MODE_FRONT:
                        tvVideoStatus.setText("Видео: Фронтальная (1 FPS)");
                        break;
                    case VIDEO_MODE_BACK:
                        tvVideoStatus.setText("Видео: Камера (1 FPS)");
                        break;
                    case VIDEO_MODE_SCREEN:
                        tvVideoStatus.setText("Видео: Экран (1 FPS)");
                        break;
                    case VIDEO_MODE_NONE:
                    default:
                        tvVideoStatus.setText("Видео: выкл");
                        break;
                }
            }
        });

        switch (mode) {
            case VIDEO_MODE_FRONT:
            case VIDEO_MODE_BACK:
                startCameraPreview(mode);
                break;
            case VIDEO_MODE_SCREEN:
                startScreenCapture();
                break;
            case VIDEO_MODE_NONE:
            default:
                stopCamera();
                stopScreenCapture();
                break;
        }
    }

    private void startCameraPreview(int mode) {
        stopCamera();
        stopScreenCapture();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSIONS_REQUEST_CAMERA);
            return;
        }

        startCameraBackgroundThread();

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) return;

        try {
            String targetCameraId = null;
            int facing = (mode == VIDEO_MODE_FRONT) ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;

            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (lensFacing != null && lensFacing == facing) {
                    targetCameraId = id;
                    break;
                }
            }

            if (targetCameraId == null && manager.getCameraIdList().length > 0) {
                targetCameraId = manager.getCameraIdList()[0];
            }

            if (targetCameraId == null) {
                Toast.makeText(this, "Камера не найдена", Toast.LENGTH_SHORT).show();
                return;
            }

            final String selectedCameraId = targetCameraId;

            if (cameraTextureView != null) {
                if (cameraTextureView.isAvailable()) {
                    openCameraDevice(manager, selectedCameraId);
                } else {
                    cameraTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                        @Override
                        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                            openCameraDevice(manager, selectedCameraId);
                        }
                        @Override
                        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
                        @Override
                        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                            stopCamera();
                            return true;
                        }
                        @Override
                        public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
                    });
                }
            }
        } catch (Exception e) {
            Log.e("Camera", "Error setting up camera", e);
        }
    }

    private void openCameraDevice(CameraManager manager, String cameraId) {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createCameraPreviewSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    Log.e("Camera", "CameraDevice error: " + error);
                }
            }, cameraBackgroundHandler);
        } catch (Exception e) {
            Log.e("Camera", "Error opening camera device", e);
        }
    }

    private void createCameraPreviewSession() {
        try {
            if (cameraTextureView == null || cameraDevice == null) return;
            SurfaceTexture texture = cameraTextureView.getSurfaceTexture();
            if (texture == null) return;

            texture.setDefaultBufferSize(640, 480);
            Surface surface = new Surface(texture);

            final CaptureRequest.Builder previewRequestBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);
            previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            cameraDevice.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            cameraCaptureSession = session;
                            try {
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                        CaptureRequest.CONTROL_AE_MODE_ON);
                                cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, cameraBackgroundHandler);
                            } catch (Exception e) {
                                Log.e("Camera", "Error starting camera preview", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e("Camera", "Camera configuration failed");
                        }
                    }, cameraBackgroundHandler);
        } catch (Exception e) {
            Log.e("Camera", "Error creating preview session", e);
        }
    }

    private void stopCamera() {
        try {
            if (cameraCaptureSession != null) {
                cameraCaptureSession.stopRepeating();
                cameraCaptureSession.close();
                cameraCaptureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            stopCameraBackgroundThread();
        } catch (Exception e) {
            Log.e("Camera", "Error stopping camera", e);
        }
    }

    private void startCameraBackgroundThread() {
        if (cameraBackgroundThread == null) {
            cameraBackgroundThread = new HandlerThread("CameraBackground");
            cameraBackgroundThread.start();
            cameraBackgroundHandler = new Handler(cameraBackgroundThread.getLooper());
        }
    }

    private void stopCameraBackgroundThread() {
        if (cameraBackgroundThread != null) {
            cameraBackgroundThread.quitSafely();
            try {
                cameraBackgroundThread.join();
            } catch (InterruptedException e) {
                Log.e("Camera", "Interrupted stopping camera thread", e);
            }
            cameraBackgroundThread = null;
            cameraBackgroundHandler = null;
        }
    }

    private void startScreenCapture() {
        stopCamera();
        stopScreenCapture();

        if (screenCaptureIntentData != null && screenCaptureResultCode == RESULT_OK) {
            initVirtualDisplay();
        } else {
            if (mediaProjectionManager == null) {
                mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            }
            if (mediaProjectionManager != null) {
                startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
            }
        }
    }

    private void initVirtualDisplay() {
        try {
            if (mediaProjectionManager == null) {
                mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            }
            if (mediaProjection == null && mediaProjectionManager != null && screenCaptureIntentData != null) {
                mediaProjection = mediaProjectionManager.getMediaProjection(screenCaptureResultCode, screenCaptureIntentData);
            }
            if (mediaProjection == null) return;

            DisplayMetrics metrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(metrics);
            int screenWidth = 640;
            int screenHeight = (int) (640.0 * metrics.heightPixels / metrics.widthPixels);
            if (screenWidth % 2 != 0) screenWidth--;
            if (screenHeight % 2 != 0) screenHeight--;

            if (imageReaderScreen != null) {
                imageReaderScreen.close();
            }
            imageReaderScreen = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenCapture",
                    screenWidth, screenHeight, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReaderScreen.getSurface(), null, null
            );

        } catch (Exception e) {
            Log.e("ScreenCapture", "Error initializing virtual display", e);
        }
    }

    private void stopScreenCapture() {
        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
            if (imageReaderScreen != null) {
                imageReaderScreen.close();
                imageReaderScreen = null;
            }
            if (mediaProjection != null) {
                mediaProjection.stop();
                mediaProjection = null;
            }
        } catch (Exception e) {
            Log.e("ScreenCapture", "Error stopping screen capture", e);
        }
    }

    private void captureAndSendVideoFrame(final String streamId) {
        if (streamId == null || currentVideoMode == VIDEO_MODE_NONE) return;

        if (currentVideoMode == VIDEO_MODE_SCREEN) {
            videoExecutor.execute(() -> captureAndSendScreenFrame(streamId));
        } else if (currentVideoMode == VIDEO_MODE_FRONT || currentVideoMode == VIDEO_MODE_BACK) {
            runOnUiThread(() -> {
                if (cameraTextureView != null && cameraTextureView.isAvailable()) {
                    try {
                        final Bitmap bitmap = cameraTextureView.getBitmap(640, 480);
                        if (bitmap != null) {
                            videoExecutor.execute(() -> {
                                try {
                                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos);
                                    byte[] jpegBytes = baos.toByteArray();
                                    bitmap.recycle();
                                    String b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP);
                                    sendVideoChunk(b64, streamId);
                                } catch (Exception e) {
                                    Log.e("VideoStream", "Error compressing camera frame", e);
                                }
                            });
                        }
                    } catch (Exception e) {
                        Log.e("VideoStream", "Error capturing TextureView bitmap", e);
                    }
                }
            });
        }
    }

    private void captureAndSendScreenFrame(String streamId) {
        if (imageReaderScreen == null) return;
        Image image = null;
        try {
            image = imageReaderScreen.acquireLatestImage();
            if (image == null) return;

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int width = image.getWidth();
            int height = image.getHeight();
            int rowPadding = rowStride - pixelStride * width;

            Bitmap bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
            );
            bitmap.copyPixelsFromBuffer(buffer);

            Bitmap finalBitmap;
            if (rowPadding != 0) {
                finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);
                bitmap.recycle();
            } else {
                finalBitmap = bitmap;
            }

            Bitmap scaledBitmap;
            if (width > 640) {
                int scaledHeight = (int) (height * (640.0f / width));
                scaledBitmap = Bitmap.createScaledBitmap(finalBitmap, 640, scaledHeight, true);
                finalBitmap.recycle();
            } else {
                scaledBitmap = finalBitmap;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos);
            scaledBitmap.recycle();
            byte[] jpegBytes = baos.toByteArray();
            String b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP);
            sendVideoChunk(b64, streamId);

        } catch (Exception e) {
            Log.e("VideoStream", "Error capturing screen frame", e);
        } finally {
            if (image != null) {
                try { image.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void sendVideoChunk(String b64, String streamId) {
        if (b64 == null || streamId == null) return;
        try {
            JSONObject videoChunk = new JSONObject();
            videoChunk.put("type", "video_stream_chunk");
            videoChunk.put("video_base64", b64);
            videoChunk.put("ui_msg_id", streamId);
            sendEncodedMessage(videoChunk);
        } catch (JSONException e) {
            Log.e("VideoStream", "Error sending video chunk", e);
        }
    }
}
