package org.vosk.demo;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MyForegroundService extends Service {
    private static final int NOTIFICATION_ID = 123;
    private static final String CHANNEL_ID = "AssistantServiceChannel";
    private Handler handler;

    // --- Потоковое аудио ---
    private AudioTrack audioTrack;
    private Thread audioPlayThread;
    private boolean isAudioThreadRunning = false;
    private final ConcurrentLinkedQueue<byte[]> audioQueue = new ConcurrentLinkedQueue<>();
    private final Object audioLock = new Object();

    public static final String ACTION_BOT_SPEAKING = "org.vosk.demo.ACTION_BOT_SPEAKING";
    public static final String EXTRA_IS_SPEAKING = "is_speaking";

    private void broadcastSpeakingState(boolean isSpeaking) {
        Intent intent = new Intent(ACTION_BOT_SPEAKING);
        intent.putExtra(EXTRA_IS_SPEAKING, isSpeaking);
        sendBroadcast(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Notification notification = createNotification();
        startForeground(NOTIFICATION_ID, notification);

        if (intent == null) return START_STICKY;

        String action = intent.getAction();

        if ("STOP_SPEECH".equals(action)) {
            stopBotSpeaking();
            return START_STICKY;
        }

        // 1. ПОЛУЧЕН АУДИО ЧАНК
        if ("AUDIO_CHUNK".equals(action)) {
            String base64 = intent.getStringExtra("audio_base64");
            appendAudioChunk(base64);
            return START_STICKY;
        }

        // 2. ПОЛУЧЕНО СООБЩЕНИЕ / КОМАНДА
        if (intent.hasExtra("command")) {
            try {
                String commandJson = intent.getStringExtra("command");
                JSONObject commandMessage = new JSONObject(commandJson);
                String type = commandMessage.optString("type");

                if ("new_message".equals(type)) {
                    String originalCommand = commandMessage.optString("original_command", "");
                    String sourceDevice = commandMessage.optString("source_device", "");
                    String userMsgId = commandMessage.optString("user_msg_id", "");

                    // Показ уведомления в системной шторке (UI чата управляется напрямую в FridayActivity)
                    String displayMessageText = commandMessage.optString("text", "").trim();
                    if (!displayMessageText.isEmpty() && !displayMessageText.equals("[Системная команда]")) {
                        String cleanText = displayMessageText;
                        if (cleanText.matches("^(?i)(бот|bot|пятница):\\s*.*")) {
                            cleanText = cleanText.replaceFirst("^(?i)(бот|bot|пятница):\\s*", "");
                        }
                        showNotificationWithText(cleanText);
                    }

                    // Воспроизведение Native Audio
                    if (commandMessage.has("audio_base64") && !commandMessage.isNull("audio_base64")) {
                        String audioBase64 = commandMessage.getString("audio_base64");
                        appendAudioChunk(audioBase64);
                    }

                    boolean needsDataResponse = false;
                    boolean needProcesses = false;
                    boolean needPrograms = false;

                    // Выполнение действий (строго если actions не пустой!)
                    if (commandMessage.has("actions") && !commandMessage.isNull("actions")) {
                        JSONArray actions = commandMessage.getJSONArray("actions");
                        if (actions.length() > 0) {
                            FridayActivity activity = FridayActivity.getInstance();
                            if (activity != null) {
                                activity.runOnUiThread(activity::resetCurrentBotResponse);
                            }
                            for (int i = 0; i < actions.length(); i++) {
                                JSONObject actionObj = actions.getJSONObject(i);
                                String actionType = actionObj.optString("action_type", "").toLowerCase();
                                String actionValue = actionObj.optString("action_value", "");

                                if (actionType.equals("data_request")) {
                                    needsDataResponse = true;
                                    String valLower = actionValue.toLowerCase();
                                    if (valLower.contains("process") || valLower.contains("процесс")) needProcesses = true;
                                    if (valLower.contains("program") || valLower.contains("app") || valLower.contains("прилож")) needPrograms = true;
                                } else {
                                    processAction(actionType, actionValue);
                                }
                            }
                        }
                    }

                    // Сбор данных и отправка обратно (Вторичный цикл)
                    if (needsDataResponse) {
                        JSONArray programs = new JSONArray();
                        JSONArray runningApps = new JSONArray();

                        if (needProcesses) runningApps = getRunningAppsWithTitles();
                        if (needPrograms) programs = getInstalledPackages();

                        JSONObject response = new JSONObject();
                        response.put("command_to_device", originalCommand);
                        response.put("processes", runningApps.toString());
                        response.put("programs", programs);
                        response.put("source_name", sourceDevice);
                        response.put("user_msg_id", userMsgId);

                        FridayActivity activity = FridayActivity.getInstance();
                        if (activity != null) {
                            activity.sendEncodedMessage(response);
                            activity.runOnUiThread(activity::resetCurrentBotResponse);
                        }
                    }
                }
            } catch (JSONException e) {
                Log.e("CommandHandler", "Error processing command", e);
            }
        }
        return START_STICKY;
    }

    // --- ЛОГИКА ПОТОКОВОГО ВОСПРОИЗВЕДЕНИЯ ---

    private void appendAudioChunk(String base64Audio) {
        if (base64Audio == null || base64Audio.isEmpty()) return;

        byte[] pcmData = Base64.decode(base64Audio, Base64.DEFAULT);
        audioQueue.add(pcmData);

        synchronized (audioLock) {
            if (!isAudioThreadRunning) {
                isAudioThreadRunning = true;
                startAudioPlaybackThread();
            }
        }
    }

    private void startAudioPlaybackThread() {
        audioPlayThread = new Thread(() -> {
            try {
                int sampleRate = 24000;
                int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
                int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
                int bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    audioTrack = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(audioFormat)
                                    .setSampleRate(sampleRate)
                                    .setChannelMask(channelConfig)
                                    .build())
                            .setBufferSizeInBytes(bufferSize * 4)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build();
                } else {
                    audioTrack = new AudioTrack(
                            AudioManager.STREAM_MUSIC, sampleRate, channelConfig, audioFormat,
                            bufferSize * 4, AudioTrack.MODE_STREAM
                    );
                }

                audioTrack.play();
                broadcastSpeakingState(true);

                int emptyPolls = 0;
                while (isAudioThreadRunning) {
                    byte[] chunk = audioQueue.poll();
                    if (chunk != null) {
                        emptyPolls = 0;
                        audioTrack.write(chunk, 0, chunk.length);
                    } else {
                        emptyPolls++;
                        try { Thread.sleep(50); } catch (Exception ignored) {}

                        // Если новых чанков нет больше ~500мс -> Конец стрима по сети
                        if (emptyPolls > 10) {
                            break;
                        }
                    }
                }

                if (audioTrack != null && audioTrack.getState() != AudioTrack.STATE_UNINITIALIZED) {
                    audioTrack.stop();
                    Thread.sleep(1000);
                }

            } catch (Exception e) {
                Log.e("AudioStream", "Error playing native audio stream", e);
            } finally {
                cleanupAudioTrack();
            }
        });
        audioPlayThread.start();
    }

    private void cleanupAudioTrack() {
        isAudioThreadRunning = false;
        broadcastSpeakingState(false);
        audioQueue.clear();

        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception ignored) {}
            audioTrack = null;
        }
    }

    public void stopBotSpeaking() {
        isAudioThreadRunning = false;
        cleanupAudioTrack();
        handler.removeCallbacksAndMessages(null);
    }

    // --- ЛОГИКА ИСПОЛНЕНИЯ КОМАНД ---

    private void processAction(String actionType, String actionContent) {
        if (actionType == null || actionType.isEmpty()) return;

        switch (actionType) {
            case "голосовой ответ":
            case "текстовой ответ":
            case "субтитры":
            case "data_request":
                break;
            case "открытие ссылки":
            case "ссылка":
            case "открыть ссылку":
                openLink(actionContent);
                break;
            case "открытие приложений":
            case "открытие приложения":
            case "запуск приложения":
            case "запуск приложений":
            case "открытие файла":
                openApp(actionContent);
                break;
            case "изменение громкости":
            case "громкость":
                changeVolumeInBackground(actionContent);
                break;
            case "изменение яркости":
            case "яркость":
                changeBrightnessInBackground(actionContent);
                break;
            case "завершение процесса":
            case "закрытие приложения":
            case "закрытие приложений":
            case "завершение приложения":
            case "закрыть приложение":
            case "закрыть процесс":
                closeApp(actionContent);
                break;
            case "музыка":
                String mLower = actionContent.toLowerCase();
                if (mLower.contains("следующ") || mLower.contains("next") || mLower.contains("дальше")) controlMediaPlayback("next");
                else if (mLower.contains("предыдущ") || mLower.contains("prev") || mLower.contains("назад")) controlMediaPlayback("previous");
                else if (mLower.contains("выключ") || mLower.contains("стоп") || mLower.contains("пауз") || mLower.contains("pause")) controlMediaPlayback("pause");
                else if (mLower.contains("включ") || mLower.contains("игра") || mLower.contains("play") || mLower.contains("продолж")) controlMediaPlayback("play");
                break;
            case "очистка истории":
            case "очистить историю":
            case "clear_history":
            case "delete_history":
                FridayActivity act = FridayActivity.getInstance();
                if (act != null) {
                    act.clearGuestHistoryLocally();
                }
                break;
            default:
                Log.w("CommandHandler", "Unknown action type: " + actionType);
        }
    }

    public void controlMediaPlayback(String action) {
        try {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) return;

            KeyEvent keyEvent;
            switch (action) {
                case "play":
                    keyEvent = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY);
                    audioManager.dispatchMediaKeyEvent(keyEvent);
                    keyEvent = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY);
                    audioManager.dispatchMediaKeyEvent(keyEvent);
                    break;
                case "pause":
                    keyEvent = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE);
                    audioManager.dispatchMediaKeyEvent(keyEvent);
                    keyEvent = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE);
                    audioManager.dispatchMediaKeyEvent(keyEvent);
                    break;
                case "next":
                    keyEvent = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT);
                    audioManager.dispatchMediaKeyEvent(keyEvent);
                    keyEvent = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT);
                    audioManager.dispatchMediaKeyEvent(keyEvent);
                    break;
                case "previous":
                    keyEvent = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                    audioManager.dispatchMediaKeyEvent(keyEvent);
                    keyEvent = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                    audioManager.dispatchMediaKeyEvent(keyEvent);
                    break;
            }
        } catch (Exception e) {
            Log.e("MediaControl", "Error controlling media playback", e);
        }
    }

    // --- УМНЫЙ ЗАПУСК ПРИЛОЖЕНИЙ ---

    public void openApp(String appQuery) {
        if (appQuery == null || appQuery.trim().isEmpty()) {
            showToast("Не указано приложение для запуска");
            return;
        }

        String query = appQuery.trim();
        // Если пришло в формате "Название (com.package.name)" -> извлекаем package name
        if (query.contains("(") && query.endsWith(")")) {
            int openIdx = query.lastIndexOf("(");
            int closeIdx = query.lastIndexOf(")");
            if (openIdx < closeIdx) {
                String potentialPkg = query.substring(openIdx + 1, closeIdx).trim();
                if (launchPackage(potentialPkg)) {
                    return;
                }
                query = query.substring(0, openIdx).trim();
            }
        }

        // 1. Попытка запустить напрямую как имя пакета
        if (launchPackage(query)) {
            return;
        }

        // 2. Умный поиск среди всех установленных приложений с лаунчером
        PackageManager pm = getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> launchables = pm.queryIntentActivities(mainIntent, 0);

        String cleanQuery = query.toLowerCase().replaceAll("[^a-zA-Zа-яА-Я0-9]", "");

        ResolveInfo bestMatch = null;
        int bestScore = 0;

        for (ResolveInfo ri : launchables) {
            String label = ri.loadLabel(pm).toString();
            String pkg = ri.activityInfo.packageName;
            String cleanLabel = label.toLowerCase().replaceAll("[^a-zA-Zа-яА-Я0-9]", "");
            String cleanPkg = pkg.toLowerCase();

            // Точное совпадение по имени или пакету
            if (label.equalsIgnoreCase(query) || pkg.equalsIgnoreCase(query) || cleanLabel.equals(cleanQuery)) {
                bestMatch = ri;
                bestScore = 100;
                break;
            }

            // Пакет содержит запрос (например "telegram" в "org.telegram.messenger")
            if (cleanPkg.contains(cleanQuery) && bestScore < 80) {
                bestMatch = ri;
                bestScore = 80;
            }

            // Имя начинается с запроса
            if (cleanLabel.startsWith(cleanQuery) && bestScore < 70) {
                bestMatch = ri;
                bestScore = 70;
            }

            // Имя содержит запрос
            if (cleanLabel.contains(cleanQuery) && bestScore < 60) {
                bestMatch = ri;
                bestScore = 60;
            }

            // Запрос содержит имя (например, "открой телеграм" -> "телеграм")
            if (cleanQuery.contains(cleanLabel) && cleanLabel.length() > 2 && bestScore < 50) {
                bestMatch = ri;
                bestScore = 50;
            }

            // Проверка по словарю синонимов / алиасов
            if (checkAliasMatch(cleanQuery, cleanLabel, cleanPkg) && bestScore < 75) {
                bestMatch = ri;
                bestScore = 75;
            }
        }

        if (bestMatch != null) {
            String targetPkg = bestMatch.activityInfo.packageName;
            String targetLabel = bestMatch.loadLabel(pm).toString();
            if (launchPackage(targetPkg)) {
                showToast("Открыто: " + targetLabel);
                return;
            }
        }

        // 3. Fallback: стандартные системные Intent (камера, калькулятор, часы, настройки, браузер)
        if (launchStandardIntent(query)) {
            return;
        }

        // 4. Fallback: поиск по всем ApplicationInfo (включая системные без CATEGORY_LAUNCHER)
        try {
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : apps) {
                String label = pm.getApplicationLabel(ai).toString();
                if (label.equalsIgnoreCase(query) || ai.packageName.equalsIgnoreCase(query)) {
                    if (launchPackage(ai.packageName)) {
                        showToast("Открыто: " + label);
                        return;
                    }
                }
            }
        } catch (Exception ignored) {}

        showToast("Приложение \"" + query + "\" не найдено");
    }

    private boolean launchPackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        try {
            PackageManager pm = getPackageManager();
            Intent launchIntent = pm.getLaunchIntentForPackage(packageName.trim());
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(launchIntent);
                return true;
            }
        } catch (Exception e) {
            Log.e("LaunchPackage", "Error launching " + packageName, e);
        }
        return false;
    }

    private boolean checkAliasMatch(String cleanQuery, String cleanLabel, String cleanPkg) {
        if (cleanQuery.contains("ютуб") || cleanQuery.contains("youtube")) {
            return cleanPkg.contains("youtube") || cleanLabel.contains("youtube");
        }
        if (cleanQuery.contains("телег") || cleanQuery.contains("telegram")) {
            return cleanPkg.contains("telegram") || cleanLabel.contains("telegram");
        }
        if (cleanQuery.contains("хром") || cleanQuery.contains("chrome") || cleanQuery.contains("браузер") || cleanQuery.contains("browser")) {
            return cleanPkg.contains("chrome") || cleanPkg.contains("browser") || cleanLabel.contains("chrome");
        }
        if (cleanQuery.contains("ватсап") || cleanQuery.contains("вацап") || cleanQuery.contains("whatsapp")) {
            return cleanPkg.contains("whatsapp");
        }
        if (cleanQuery.contains("вк") || cleanQuery.contains("вконтакте") || cleanQuery.contains("vk")) {
            return cleanPkg.contains("vkontakte") || cleanLabel.contains("vk");
        }
        if (cleanQuery.contains("камер") || cleanQuery.contains("camera")) {
            return cleanPkg.contains("camera") || cleanLabel.contains("камер");
        }
        if (cleanQuery.contains("калькулят") || cleanQuery.contains("calculator")) {
            return cleanPkg.contains("calculator") || cleanLabel.contains("калькулятор");
        }
        if (cleanQuery.contains("настройк") || cleanQuery.contains("settings") || cleanQuery.contains("параметр")) {
            return cleanPkg.contains("settings") || cleanLabel.contains("настройки");
        }
        if (cleanQuery.contains("галере") || cleanQuery.contains("фото") || cleanQuery.contains("gallery") || cleanQuery.contains("photos")) {
            return cleanPkg.contains("gallery") || cleanPkg.contains("photos") || cleanLabel.contains("галерея") || cleanLabel.contains("фото");
        }
        if (cleanQuery.contains("час") || cleanQuery.contains("будильник") || cleanQuery.contains("clock") || cleanQuery.contains("alarm")) {
            return cleanPkg.contains("clock") || cleanLabel.contains("часы") || cleanLabel.contains("будильник");
        }
        if (cleanQuery.contains("музык") || cleanQuery.contains("music") || cleanQuery.contains("плеер")) {
            return cleanPkg.contains("music") || cleanLabel.contains("музыка");
        }
        if (cleanQuery.contains("карт") || cleanQuery.contains("maps") || cleanQuery.contains("навигатор")) {
            return cleanPkg.contains("maps") || cleanPkg.contains("yandexnavi") || cleanLabel.contains("карты");
        }
        if (cleanQuery.contains("контакт") || cleanQuery.contains("contacts")) {
            return cleanPkg.contains("contacts") || cleanLabel.contains("контакты");
        }
        if (cleanQuery.contains("телефон") || cleanQuery.contains("звонк") || cleanQuery.contains("dialer") || cleanQuery.contains("phone")) {
            return cleanPkg.contains("dialer") || cleanPkg.contains("phone") || cleanLabel.contains("телефон");
        }
        if (cleanQuery.contains("сообщен") || cleanQuery.contains("смс") || cleanQuery.contains("sms") || cleanQuery.contains("messages")) {
            return cleanPkg.contains("messaging") || cleanPkg.contains("messages") || cleanLabel.contains("сообщения");
        }
        return false;
    }

    private boolean launchStandardIntent(String query) {
        String q = query.toLowerCase();
        try {
            Intent intent = null;
            if (q.contains("камер") || q.contains("camera")) {
                intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
            } else if (q.contains("настройк") || q.contains("settings")) {
                intent = new Intent(Settings.ACTION_SETTINGS);
            } else if (q.contains("будильник") || q.contains("alarm")) {
                intent = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
            } else if (q.contains("браузер") || q.contains("интернет") || q.contains("гугл")) {
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://google.com"));
            } else if (q.contains("калькулят") || q.contains("calc")) {
                intent = new Intent();
                intent.setAction(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_APP_CALCULATOR);
            } else if (q.contains("карт") || q.contains("maps")) {
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"));
            }

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivity(intent);
                    showToast("Приложение открыто");
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e("StandardIntent", "Error launching standard intent", e);
        }
        return false;
    }

    // --- УМНОЕ ЗАКРЫТИЕ ПРИЛОЖЕНИЙ ---

    public void closeApp(String appQuery) {
        if (appQuery == null || appQuery.trim().isEmpty()) {
            showToast("Не указано приложение для закрытия");
            return;
        }

        String query = appQuery.trim();
        // Если пришло "Название (com.pkg.name)"
        if (query.contains("(") && query.endsWith(")")) {
            int openIdx = query.lastIndexOf("(");
            int closeIdx = query.lastIndexOf(")");
            if (openIdx < closeIdx) {
                query = query.substring(openIdx + 1, closeIdx).trim();
            }
        }

        String targetPackage = query;
        String displayTitle = query;
        PackageManager pm = getPackageManager();

        // Проверяем, является ли query известным package
        try {
            ApplicationInfo ai = pm.getApplicationInfo(query, 0);
            displayTitle = pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            // Ищем подходящее приложение по имени
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> launchables = pm.queryIntentActivities(mainIntent, 0);

            String cleanQuery = query.toLowerCase().replaceAll("[^a-zA-Zа-яА-Я0-9]", "");
            for (ResolveInfo ri : launchables) {
                String label = ri.loadLabel(pm).toString();
                String pkg = ri.activityInfo.packageName;
                String cleanLabel = label.toLowerCase().replaceAll("[^a-zA-Zа-яА-Я0-9]", "");

                if (label.equalsIgnoreCase(query) || cleanLabel.contains(cleanQuery) || pkg.toLowerCase().contains(cleanQuery) ||
                        checkAliasMatch(cleanQuery, cleanLabel, pkg.toLowerCase())) {
                    targetPackage = pkg;
                    displayTitle = label;
                    break;
                }
            }
        }

        // Защита системных процессов от закрытия
        if (isSystemProtectedPackage(targetPackage)) {
            showToast("Системное приложение " + displayTitle + " защищено от закрытия");
            return;
        }

        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && am != null) {
                List<ActivityManager.AppTask> tasks = am.getAppTasks();
                if (tasks != null) {
                    for (ActivityManager.AppTask task : tasks) {
                        ActivityManager.RecentTaskInfo taskInfo = task.getTaskInfo();
                        if (taskInfo.baseIntent != null && taskInfo.baseIntent.getComponent() != null) {
                            if (taskInfo.baseIntent.getComponent().getPackageName().equals(targetPackage)) {
                                task.finishAndRemoveTask();
                            }
                        }
                    }
                }
            }

            if (am != null) {
                am.killBackgroundProcesses(targetPackage);
            }

            // Возвращаем на главный экран, если закрытое приложение было активно
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(homeIntent);

            showToast("Приложение " + displayTitle + " закрыто");
        } catch (Exception e) {
            Log.e("CloseApp", "Error closing app " + targetPackage, e);
            showToast("Не удалось закрыть " + displayTitle);
        }
    }

    private boolean isSystemProtectedPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase();
        return p.equals("android") ||
                p.equals(getPackageName().toLowerCase()) ||
                p.contains("com.android.systemui") ||
                p.contains("com.android.launcher") ||
                p.contains("com.google.android.apps.nexuslauncher") ||
                p.contains("com.sec.android.app.launcher") ||
                p.contains("com.miui.home") ||
                p.contains("com.huawei.android.launcher");
    }

    public void showNotificationWithText(String text) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        Intent intent = new Intent(this, FridayActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.icon)
                .setContentTitle("Ответ ассистента")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        try {
            if (notificationManager != null) {
                notificationManager.notify(12345, builder.build());
            }
        } catch (SecurityException ignored) {}
    }

    public void openLink(String url) {
        try {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://" + url;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            PackageManager pm = getPackageManager();
            if (intent.resolveActivity(pm) != null) {
                startActivity(intent);
            } else {
                showToast("Не найдено приложение для открытия ссылки");
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"));
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (browserIntent.resolveActivity(pm) != null) startActivity(browserIntent);
            }
        } catch (Exception e) {
            showToast("Ошибка при открытии ссылки");
        }
    }

    public void changeVolumeInBackground(String volumeValue) {
        try {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null || volumeValue == null) return;

            String trimmed = volumeValue.trim();
            int maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int currentMusic = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

            if (trimmed.toLowerCase().contains("тише") || trimmed.startsWith("-")) {
                int delta = 10;
                String digits = trimmed.replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) delta = Integer.parseInt(digits);
                int target = Math.max(0, currentMusic - (int) Math.round(maxMusic * (delta / 100.0)));
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_PLAY_SOUND);
                return;
            }

            if (trimmed.toLowerCase().contains("громче") || trimmed.startsWith("+")) {
                int delta = 10;
                String digits = trimmed.replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) delta = Integer.parseInt(digits);
                int target = Math.min(maxMusic, currentMusic + (int) Math.round(maxMusic * (delta / 100.0)));
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_PLAY_SOUND);
                return;
            }

            String digits = trimmed.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                int percent = Integer.parseInt(digits);
                percent = Math.max(0, Math.min(100, percent));
                int targetMusic = (int) Math.round(maxMusic * (percent / 100.0));
                audioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC, targetMusic, AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_PLAY_SOUND
                );
            }
        } catch (Exception e) {
            Log.e("VolumeControl", "Error changing volume", e);
        }
    }

    public void changeBrightnessInBackground(String brightnessValue) {
        try {
            if (brightnessValue == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.System.canWrite(this)) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    showToast("Разрешите изменение настроек яркости");
                    return;
                }
            }

            String digits = brightnessValue.replaceAll("[^0-9]", "");
            if (digits.isEmpty()) return;
            int percent = Integer.parseInt(digits);
            percent = Math.max(0, Math.min(100, percent));
            int brightness = (int) Math.round(255.0 * percent / 100.0);

            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, brightness);
            showToast("Яркость установлена на " + percent + "%");
        } catch (Exception e) {
            Log.e("BrightnessControl", "Error changing brightness", e);
        }
    }

    private void showToast(final String message) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show());
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, FridayActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Голосовой ассистент")
                .setContentText("Работает в фоновом режиме")
                .setSmallIcon(R.drawable.icon)
                .setContentIntent(pendingIntent)
                .build();
    }

    public void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Фоновый сервис ассистента",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopBotSpeaking();
        stopForeground(true);
        stopSelf();
    }

    public JSONArray getInstalledPackages() {
        JSONArray programs = new JSONArray();
        try {
            PackageManager pm = getPackageManager();
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> launchables = pm.queryIntentActivities(mainIntent, 0);

            HashSet<String> added = new HashSet<>();
            for (ResolveInfo ri : launchables) {
                String pkg = ri.activityInfo.packageName;
                if (!added.contains(pkg)) {
                    added.add(pkg);
                    String label = ri.loadLabel(pm).toString();
                    programs.put(label + " (" + pkg + ")");
                }
            }

            // Добавляем также сторонние приложения без лаунчера (сервисы и т.д.)
            List<ApplicationInfo> packages = pm.getInstalledApplications(0);
            for (ApplicationInfo packageInfo : packages) {
                if ((packageInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0 && !added.contains(packageInfo.packageName)) {
                    added.add(packageInfo.packageName);
                    String label = pm.getApplicationLabel(packageInfo).toString();
                    programs.put(label + " (" + packageInfo.packageName + ")");
                }
            }
        } catch (Exception e) {
            Log.e("Programs", "Error fetching installed packages", e);
        }
        return programs;
    }

    private JSONArray getRunningAppsWithTitles() {
        JSONArray runningApps = new JSONArray();
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        PackageManager pm = getPackageManager();
        if (am == null || pm == null) return runningApps;

        HashSet<String> added = new HashSet<>();

        // 1. Через Recent Tasks / AppTasks (Android 5.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                List<ActivityManager.AppTask> tasks = am.getAppTasks();
                if (tasks != null) {
                    for (ActivityManager.AppTask task : tasks) {
                        ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                        if (info.baseIntent != null && info.baseIntent.getComponent() != null) {
                            String pkg = info.baseIntent.getComponent().getPackageName();
                            if (!added.contains(pkg) && !pkg.equals(getPackageName())) {
                                added.add(pkg);
                                try {
                                    ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                                    String appName = pm.getApplicationLabel(appInfo).toString();
                                    runningApps.put(appName + " (" + pkg + ")");
                                } catch (PackageManager.NameNotFoundException ignored) {
                                    runningApps.put(pkg);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 2. Через running processes
        List<ActivityManager.RunningAppProcessInfo> runningProcesses = am.getRunningAppProcesses();
        if (runningProcesses != null) {
            for (ActivityManager.RunningAppProcessInfo processInfo : runningProcesses) {
                if (processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                        processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                    if (processInfo.pkgList != null) {
                        for (String pkg : processInfo.pkgList) {
                            if (!added.contains(pkg) && !pkg.equals(getPackageName())) {
                                added.add(pkg);
                                try {
                                    ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                                    String appName = pm.getApplicationLabel(appInfo).toString();
                                    runningApps.put(appName + " (" + pkg + ")");
                                } catch (PackageManager.NameNotFoundException ignored) {
                                    runningApps.put(pkg);
                                }
                            }
                        }
                    }
                }
            }
        }
        return runningApps;
    }
}
