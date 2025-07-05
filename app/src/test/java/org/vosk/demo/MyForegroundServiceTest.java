package org.vosk.demo;

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.view.Window;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class MyForegroundServiceTest {

    private MyForegroundService service;

    @Mock private TextToSpeech mockTts;
    @Mock private NotificationManager mockNotificationManager;
    @Mock private AudioManager mockAudioManager;
    @Mock private ActivityManager mockActivityManager;
    @Mock private PackageManager mockPackageManager;
    @Mock private Window mockWindow;
    @Mock private WindowManager.LayoutParams mockLayoutParams;

    @Before
    public void setUp() {
        // Инициализация сервиса с правильным контекстом
        service = new MyForegroundService();
        service = spy(service);

        // Мокируем системные сервисы
        doReturn(mockNotificationManager).when(service).getSystemService(Context.NOTIFICATION_SERVICE);
        doReturn(mockAudioManager).when(service).getSystemService(Context.AUDIO_SERVICE);
        doReturn(mockActivityManager).when(service).getSystemService(Context.ACTIVITY_SERVICE);
        doReturn(mockPackageManager).when(service).getPackageManager();

        // Инициализация TTS
        service.textToSpeech = mockTts;

        // Мокируем FridayActivity
        FridayActivity mockActivity = mock(FridayActivity.class);
        when(mockActivity.getWindow()).thenReturn(mockWindow);
        when(mockWindow.getAttributes()).thenReturn(mockLayoutParams);
        FridayActivity.setInstance(mockActivity);
    }

    @Test
    public void testChangeVolumeInBackground() {
        String volumeCommand = "50%";
        service.changeVolumeInBackground(volumeCommand);

        verify(mockAudioManager).setStreamVolume(
                eq(AudioManager.STREAM_MUSIC),
                anyInt(),
                anyInt()
        );
    }

    @Test
    public void testControlMediaPlayback() {
        service.controlMediaPlayback("play");
        verify(mockAudioManager, times(2)).dispatchMediaKeyEvent(any());
    }

    @Test
    public void testOpenAppInBackground() {
        String packageName = "com.example.app";
        Intent mockIntent = mock(Intent.class);
        when(mockPackageManager.getLaunchIntentForPackage(packageName)).thenReturn(mockIntent);

        service.openAppInBackground(packageName);
        verify(service).startActivity(mockIntent);
    }
}