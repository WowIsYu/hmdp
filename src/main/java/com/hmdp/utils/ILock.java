package com.hmdp.utils;

interface ILock {
    /**
     * 尝试获取锁
     * @param timeoutSec
     * @return
     */
    public boolean tryLock(long timeoutSec);


    /**
     * 释放锁
     */
    public void unlock();
}
