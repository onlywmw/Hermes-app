package com.hermes.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple RecyclerView adapter for the Hermes chat messages.
 */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ViewHolder> {

    public static class Message {
        public final String sender;
        public final String text;

        public Message(String sender, String text) {
            this.sender = sender;
            this.text = text;
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final TextView sender;
        public final TextView text;

        public ViewHolder(View view) {
            super(view);
            sender = view.findViewById(R.id.chat_sender);
            text = view.findViewById(R.id.chat_text);
        }
    }

    private final List<Message> mMessages = new ArrayList<>();

    public void addMessage(Message message) {
        mMessages.add(message);
        notifyItemInserted(mMessages.size() - 1);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_chat_message, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Message message = mMessages.get(position);
        holder.sender.setText(message.sender);
        holder.text.setText(message.text);
    }

    @Override
    public int getItemCount() {
        return mMessages.size();
    }
}
