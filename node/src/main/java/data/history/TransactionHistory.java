package data.history;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class TransactionHistory {
    public enum DrCrIndicator {
        DR, CR
    }

    private final DrCrIndicator drCrIndicator;
    private final long timestamp;
    private final float transactionAmount;
    private final String senderKey;
    private final String recipientKey;
    private final String hash;

    public TransactionHistory(DrCrIndicator drCrIndicator, long timestamp, float transactionAmount, String senderKey,
                              String recipientKey, String hash) {
        this.drCrIndicator = drCrIndicator;
        this.timestamp = timestamp;
        this.transactionAmount = transactionAmount;
        this.senderKey = senderKey;
        this.recipientKey = recipientKey;
        this.hash = hash;
    }

    public String getHash() {
        return hash;
    }

    public DrCrIndicator getDrCrIndicator() {
        return drCrIndicator;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getTimestampAsString() {
        return Instant.ofEpochMilli(this.timestamp)
            .atZone(ZoneId.of("GMT"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public float getTransactionAmount() {
        return transactionAmount;
    }

    public String getSenderKey() {
        return senderKey;
    }

    public String getRecipientKey() {
        return recipientKey;
    }

    public String getTransactionLine() {
        // Display the opposite key for the user
        String secondParty = drCrIndicator.equals(DrCrIndicator.DR)
                ? senderKey
                : recipientKey;

        return getTimestampAsString().concat(" ")
                .concat(drCrIndicator.name()).concat(" ")
                .concat(String.valueOf(transactionAmount)).concat("\n")
                .concat(secondParty).concat("\n\n");
    }

    @Override
    public String toString() {
        return "TransactionHistory{" +
                "drCrIndicator=" + drCrIndicator +
                ", timestamp=" + timestamp +
                ", transactionAmount=" + transactionAmount +
                ", senderKey='" + senderKey + '\'' +
                ", recipientKey='" + recipientKey + '\'' +
                ", hash='" + hash + '\'' +
                '}';
    }
}
