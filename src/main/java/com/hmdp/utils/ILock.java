package com.hmdp.utils;


public interface ILock {
    boolean tryLock(long timeoutSec) throws InterruptedException;
    void unlock();
}
