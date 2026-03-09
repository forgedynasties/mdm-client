package com.aioapp.mdm;

import java.util.concurrent.locks.ReentrantLock;

public class ServiceSyncManager {
    private static final ServiceSyncManager INSTANCE = new ServiceSyncManager();
    private final ReentrantLock lock = new ReentrantLock();

    private ServiceSyncManager() {}

    public static ServiceSyncManager getInstance() {
        return INSTANCE;
    }

    public ReentrantLock getLock() {
        return lock;
    }
}