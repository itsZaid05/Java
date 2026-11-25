package com.chatsphere.common.model;
/** One stored message. timestamp = epoch milliseconds UTC. Room messages always have status SENT. */
public record MessageRecord(long id, String sender, long timestamp, DeliveryStatus status, String text, Integer attachmentSize) {
    public MessageRecord(long id, String sender, long timestamp, DeliveryStatus status, String text) {
        this(id, sender, timestamp, status, text, null);
    }
}
