package com.example.sqlmcpchatopenrouter.chat;

@FunctionalInterface
public interface ProgressSink {

    void progress(String stage, String message);

    static ProgressSink none() {
        return (stage, message) -> {
        };
    }
}
