package org.face_recognition;

import java.io.Serializable;

class FileTransferData implements Serializable {
    private String recipientName;
    private byte[] fileChunk;
    private boolean isLastChunk;

    public FileTransferData(String recipientName, byte[] fileChunk, boolean isLastChunk) {
        this.recipientName = recipientName;
        this.fileChunk = fileChunk;
        this.isLastChunk = isLastChunk;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public byte[] getFileChunk() {
        return fileChunk;
    }

    public boolean isLastChunk() {
        return isLastChunk;
    }
}
