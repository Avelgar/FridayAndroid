package org.vosk.demo;

public class ChatMessage {
    public static final int TYPE_USER = 0;
    public static final int TYPE_BOT = 1;

    private String id;        // Real message id (from server)
    private String uiMsgId;   // Local temp id used until server assigns real id
    private String text;
    private int type;         // TYPE_USER or TYPE_BOT
    private String time;      // Formatted time string

    public ChatMessage(String id, String uiMsgId, String text, int type, String time) {
        this.id = id;
        this.uiMsgId = uiMsgId;
        this.text = text;
        this.type = type;
        this.time = time;
    }

    public static ChatMessage createUser(String text) {
        return new ChatMessage(null, null, text, TYPE_USER, null);
    }

    public static ChatMessage createBot(String text) {
        return new ChatMessage(null, null, text, TYPE_BOT, null);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUiMsgId() { return uiMsgId; }
    public void setUiMsgId(String uiMsgId) { this.uiMsgId = uiMsgId; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public int getType() { return type; }

    public String getTime() { return time; }
}
