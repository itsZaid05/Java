package com.chatsphere.client.view.workspace.state;

import com.chatsphere.common.model.DeliveryStatus;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import java.time.LocalDate;

/**
 * Sealed hierarchy for items rendered in the message list.
 * Owned by Person 4.
 */
public sealed interface ChatListItem {

    record DaySeparatorItem(LocalDate date, String label) implements ChatListItem {}

    record UnreadDividerItem(int count, String text) implements ChatListItem {}

    record SystemItem(String text) implements ChatListItem {}

    final class MessageItem implements ChatListItem {
        private Long id;
        private final String tempId;
        private final String sender;
        private final String text;
        private final Integer attachmentSize;
        private long timestamp;
        private final ObjectProperty<DeliveryStatus> status;
        private final boolean own;
        private boolean firstInGroup;
        private boolean lastInGroup;

        public MessageItem(Long id, String tempId, String sender, String text, long timestamp,
                           DeliveryStatus status, boolean own) {
            this(id, tempId, sender, text, timestamp, status, own, null);
        }

        public MessageItem(Long id, String tempId, String sender, String text, long timestamp,
                           DeliveryStatus status, boolean own, Integer attachmentSize) {
            this.id = id;
            this.tempId = tempId;
            this.sender = sender;
            this.text = text;
            this.attachmentSize = attachmentSize;
            this.timestamp = timestamp;
            this.status = new SimpleObjectProperty<>(status);
            this.own = own;
            this.firstInGroup = true;
            this.lastInGroup = true;
        }

        public Long id() { return id; }
        public void setId(Long id) { this.id = id; }
        public String tempId() { return tempId; }
        public String sender() { return sender; }
        public String text() { return text; }
        public Integer attachmentSize() { return attachmentSize; }
        public long timestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        public DeliveryStatus status() { return status.get(); }
        public ObjectProperty<DeliveryStatus> statusProperty() { return status; }
        public void setStatus(DeliveryStatus s) { this.status.set(s); }
        public boolean isOwn() { return own; }
        public boolean isFirstInGroup() { return firstInGroup; }
        public void setFirstInGroup(boolean first) { this.firstInGroup = first; }
        public boolean isLastInGroup() { return lastInGroup; }
        public void setLastInGroup(boolean last) { this.lastInGroup = last; }
    }
}
