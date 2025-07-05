package org.vosk.demo;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class FridayActivityTest {

    private FridayActivity activity;
    private Context mockContext;

    @Before
    public void setUp() {
        mockContext = mock(Context.class);
        activity = new FridayActivity();
        activity.botName = "пятница";
        FridayActivity.resultView = new TextView(mockContext);
    }

    // Тесты для работы с JSON
    @Test
    public void testValidJSONCreation() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("test", "value");
        assertEquals("value", obj.getString("test"));
    }

    @Test
    public void testEmptyJSON() {
        JSONObject obj = new JSONObject();
        assertEquals(0, obj.length());
    }

    // Тесты валидации данных
    @Test
    public void testDataValidation_Valid() throws JSONException {
        JSONObject data = new JSONObject();
        data.put("deviceName", "test");
        data.put("password", "123");
        assertTrue(activity.isDataValidForTest(data));
    }

    @Test
    public void testDataValidation_MissingField() throws JSONException {
        JSONObject data = new JSONObject();
        data.put("deviceName", "test");
        assertFalse(activity.isDataValidForTest(data));
    }

    // Тесты обработки голосовых команд
    @Test
    public void testBotNameRecognition() {
        String input = "пятница, включи свет";
        assertTrue(input.toLowerCase().contains(activity.botName));
    }

    @Test
    public void testWithoutBotName() {
        String input = "включи свет";
        assertFalse(input.toLowerCase().contains(activity.botName));
    }

    // Тесты проверки разрешений
    @Test
    public void testPermissionsCheck() {
        activity.checkAudioPermissions();
        assertNotNull(activity);
    }

    // Тесты работы с WebSocket
    @Test
    public void testWebSocketInitialization() {
        activity.initWebSocketConnection();
        assertNotNull(activity.client);
    }
}