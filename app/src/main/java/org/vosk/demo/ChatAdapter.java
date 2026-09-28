package org.vosk.demo;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.MessageViewHolder> {

    private final List<ChatMessage> messages;

    public ChatAdapter(List<ChatMessage> messages) {
        this.messages = messages;
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chat_message, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        ChatMessage msg = messages.get(position);
        holder.bind(msg);
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        private final LinearLayout userContainer;
        private final LinearLayout botContainer;
        private final TextView tvUserText;
        private final TextView tvUserTime;
        private final TextView tvBotText;
        private final TextView tvBotTime;

        public MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            userContainer = itemView.findViewById(R.id.user_bubble_container);
            botContainer = itemView.findViewById(R.id.bot_bubble_container);
            tvUserText = itemView.findViewById(R.id.tv_user_text);
            tvUserTime = itemView.findViewById(R.id.tv_user_time);
            tvBotText = itemView.findViewById(R.id.tv_bot_text);
            tvBotTime = itemView.findViewById(R.id.tv_bot_time);
        }

        public void bind(ChatMessage msg) {
            if (msg.getType() == ChatMessage.TYPE_USER) {
                userContainer.setVisibility(View.VISIBLE);
                botContainer.setVisibility(View.GONE);
                tvUserText.setText(msg.getText());
                tvUserTime.setText(msg.getTime());
            } else {
                botContainer.setVisibility(View.VISIBLE);
                userContainer.setVisibility(View.GONE);
                tvBotText.setText(msg.getText());
                tvBotTime.setText(msg.getTime());
            }
        }
    }
}
