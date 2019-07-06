/*
 * Copyright (c) 2002, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 */


package sun.nio.ch;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * A multi-threaded implementation of Selector for Windows.
 * windows selector的多线程实现
 *
 * @author Konstantin Kladko
 * @author Mark Reinhold
 */

final class WindowsSelectorImpl extends SelectorImpl {
    /**
     * Initial capacity of the poll array
     * 初始轮询数组的容量
     */
    private final int INIT_CAP = 8;

    /**
     * Maximum number of sockets for select().
     * Should be INIT_CAP times a power of 2
     * select()方法的socket最大值
     * 尽量是2的INIT_CAP次幂
     */
    private final static int MAX_SELECTABLE_FDS = 1024;

    /**
     * The list of SelectableChannels serviced by this Selector.
     * 为该selector服务的channel集合
     * Every mod MAX_SELECTABLE_FDS entry is bogus, to align this array with the poll array,  where the corresponding entry is occupied by the wakeupSocket
     * //TODO 不是太懂??
     * 每个mod MAX_SELECTABLE_FDS条目都是假的，以便将这个数组与poll数组对齐，其中对应的条目被wakeupSocket占用
     */
    private SelectionKeyImpl[] channelArray = new SelectionKeyImpl[INIT_CAP];

    /**
     * The global native poll array holds file decriptors and event masks
     * 全局本地轮询数组包含文件描述和事件对应
     */
    private PollArrayWrapper pollWrapper;

    /**
     * The number of valid entries in poll array, including entries occupied by wakeup socket handle.
     * 在循环组中有效的条目,包含了被唤醒套接字处理占用的数目.
     */
    private int totalChannels = 1;

    /**
     * Number of helper threads needed for select. We need one thread per
     * each additional set of MAX_SELECTABLE_FDS - 1 channels.
     * select需要的帮助线程数量,每组MAX_SELECTABLE_FDS额外需要一个线程对应一个channel
     */
    private int threadsCount = 0;

    /**
     * A list of helper threads for select.
     * select 的辅助线程集合
     */
    private final List<SelectThread> threads = new ArrayList<SelectThread>();

    /**
     * Pipe used as a wakeup object.
     * 用于唤醒对象的管道.
     */
    private final Pipe wakeupPipe;

    /**
     * File descriptors corresponding to source and sink
     * 对应source和sink的文件描述
     */
    private final int wakeupSourceFd, wakeupSinkFd;

    /**
     * Lock for close cleanup
     * 为关闭的cleanup上锁
     */
    private Object closeLock = new Object();

    /**
     * Maps file descriptors to their indices in  poll Array
     * 在循环组,文件描述和其目录映射.
     * key 是文件描述对应的底层索引号,用来定位文件数据在内存中的位置
     * val 是SelectionKeyImpl
     */
    private final static class FdMap extends HashMap<Integer, MapEntry> {
        static final long serialVersionUID = 0L;

        private MapEntry get(int desc) {
            return get(new Integer(desc));
        }

        private MapEntry put(SelectionKeyImpl ski) {
            return put(ski.channel.getFDVal(), new MapEntry(ski));
        }

        private MapEntry remove(SelectionKeyImpl ski) {
            Integer fd = ski.channel.getFDVal();
            MapEntry x = get(fd);
            if ((x != null) && (x.ski.channel == ski.channel)) {
                return remove(fd);
            }
            return null;
        }
    }

    /**
     * class for fdMap entries
     */
    private final static class MapEntry {
        SelectionKeyImpl ski;
        long updateCount = 0;
        long clearedCount = 0;

        MapEntry(SelectionKeyImpl ski) {
            this.ski = ski;
        }
    }

    private final FdMap fdMap = new FdMap();

    /**
     * SubSelector for the main thread
     * 主线程的subSelector.
     */
    private final SubSelector subSelector = new SubSelector();

    /**
     * timeout for poll
     * 轮询超时时间.
     */
    private long timeout;

    /**
     * Lock for interrupt triggering and clearing
     * 用于中断触发和清除锁.(加锁或解锁)
     */
    private final Object interruptLock = new Object();
    private volatile boolean interruptTriggered = false;

    WindowsSelectorImpl(SelectorProvider sp) throws IOException {
        super(sp);
        pollWrapper = new PollArrayWrapper(INIT_CAP);
        wakeupPipe = Pipe.open();
        wakeupSourceFd = ((SelChImpl) wakeupPipe.source()).getFDVal();

        /*
         *Disable the Nagle algorithm so that the wakeup is more immediate
         *禁用Nagle算法，以便更快速地唤醒
         * //TODO Nagle算法
         */
        SinkChannelImpl sink = (SinkChannelImpl) wakeupPipe.sink();
        (sink.sc).socket().setTcpNoDelay(true);
        wakeupSinkFd = ((SelChImpl) sink).getFDVal();

        pollWrapper.addWakeupSocket(wakeupSourceFd, 0);
    }

    @Override
    protected int doSelect(long timeout) throws IOException {
        if (channelArray == null) {
            throw new ClosedSelectorException();
        }
        /*
         * set selector timeout
         * 设置selector超时时间.
         */
        this.timeout = timeout;
        processDeregisterQueue();
        if (interruptTriggered) {
            resetWakeupSocket();
            return 0;
        }
        /*
         * Calculate number of helper threads needed for poll. If necessary threads are created here and start waiting on startLock
         * 为循环组计算需要的帮助线程数,如果需要线程在这里创建,开始在startLock等待
         */
        adjustThreadsCount();
        /*
         *reset finishLock 重置
         */

        finishLock.reset();
        /* Wakeup helper threads, waiting on startLock, so they start polling. Redundant threads will exit here after wakeup.
           唤醒辅助线程,等待开始锁,于是他们开始轮询,在唤醒后多余线程将退出
         */
        startLock.startThreads();
        /* do polling in the main thread. Main thread is responsible for first MAX_SELECTABLE_FDS entries in pollArray.
           在主线程中轮询,主线程负责轮询数组中第一个MAX_SELECTABLE_FDS条目
         */
        try {
            begin();
            try {
                subSelector.poll();
            } catch (IOException e) {
                /*
                Save this exception  保存异常
                 */
                finishLock.setException(e);
            }
            /* Main thread is out of poll(). Wakeup others and wait for them
               主线程跳出poll()方法,唤醒其它线程,等待..
             */
            if (threads.size() > 0) {
                finishLock.waitForHelperThreads();
            }
        } finally {
            end();
        }

        finishLock.checkForException();
        processDeregisterQueue();
        int updated = updateSelectedKeys();
       /* Done with poll(). Set wakeupSocket to nonsignaled  for the next run.
           轮询结束,为下次运行设置唤醒线程无信号
         */
        resetWakeupSocket();
        return updated;
    }

    /**
     * Helper threads wait on this lock for the next poll.
     * 辅助线程在此锁上等待下次轮询.
     */
    private final StartLock startLock = new StartLock();

    private final class StartLock {
        /* A variable which distinguishes the current run of doSelect from the previous one.
         * 区分当前运行的doSelect与先前运行的doSelect的变量。
         * Incrementing runsCounter and notifying threads will trigger another round of poll.
         * 增加runsCounter并通知线程将触发另一轮轮询
         */
        private long runsCounter;

        /**
         * Triggers threads, waiting on this lock to start polling.
         * 触发线程,在该锁上等待开始轮询.
         */
        private synchronized void startThreads() {
            runsCounter++; // next run
            notifyAll(); // wake up threads.
        }

        /**
         * This function is called by a helper thread to wait for the  next round of poll().
         * It also checks, if this thread became redundant. If yes, it returns true, notifying the thread that it should exit.
         * 该方法被辅助线程调用,等待下次轮询.它还检查,如果该线程是否多余,如果是,返回true,通知线程它该退出
         *
         * @param thread
         * @return
         */
        private synchronized boolean waitForStart(SelectThread thread) {
            while (true) {
                while (runsCounter == thread.lastRun) {
                    try {
                        startLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                // redundant thread 多余线程
                if (thread.isZombie()) {
                    // will cause run() to exit. 使run()退出
                    return true;
                } else {
                    // update lastRun 更新lastRun
                    thread.lastRun = runsCounter;
                    //will cause run() to poll. 使run()方法轮询
                    return false;
                }
            }
        }
    }

    /**
     * Main thread waits on this lock, until all helper threads are done with poll().
     * 主线程在lock等待,直到辅助线程poll()方法结束
     */
    private final FinishLock finishLock = new FinishLock();

    private final class FinishLock {
        /* Number of helper threads, that did not finish yet.
           未完成的辅助线程数.
         */
        private int threadsToFinish;

        /* IOException which occurred during the last run.
           上次运行发送的异常.
         */
        IOException exception = null;

        /* Called before polling.
           轮询前调用.
         */
        private void reset() {
            // helper threads 辅助线程.
            threadsToFinish = threads.size();
        }

        /**
         * Each helper thread invokes this function on finishLock, when the thread is done with poll().
         * 当辅助线程完成poll()调用,都在finishLock中调用这个函数
         */
        private synchronized void threadFinished() {
            // finished poll() first 先完成poll()调用
            if (threadsToFinish == threads.size()) {
                // if finished first, wakeup others 完成后,唤醒其它
                wakeup();
            }
            threadsToFinish--;
            // all helper threads finished poll().所有辅助线程完成poll()调用.
            if (threadsToFinish == 0) {
                notify();             // notify the main thread
            }
        }

        /**
         * The main thread invokes this function on finishLock to wait for helper threads to finish poll().
         * 等到所有辅助线程完成poll()调用,在finishLock中调用这个函数
         */
        private synchronized void waitForHelperThreads() {
            if (threadsToFinish == threads.size()) {
                // no helper threads finished yet. Wakeup them up.
                wakeup();
            }
            while (threadsToFinish != 0) {
                try {
                    finishLock.wait();
                } catch (InterruptedException e) {
                    // Interrupted - set interrupted state.
                    Thread.currentThread().interrupt();
                }
            }
        }

        // sets IOException for this run
        private synchronized void setException(IOException e) {
            exception = e;
        }

        /**
         * Checks if there was any exception during the last run. If yes, throws it
         * 检查在上次运行期间是否有异常.
         *
         * @throws IOException .
         */
        private void checkForException() throws IOException {
            if (exception == null) {
                return;
            }
            StringBuffer message = new StringBuffer("An exception occurred" +
                    " during the execution of select(): \n");
            message.append(exception);
            message.append('\n');
            exception = null;
            throw new IOException(message.toString());
        }
    }

    private final class SubSelector {
        /**
         * starting index in pollArray to poll
         * 在pollArray中轮询的开始索引.
         */
        private final int pollArrayIndex;
        /**
         * These arrays will hold result of native select().
         * The first element of each array is the number of selected sockets.
         * Other elements are file descriptors of selected sockets.
         * 这些数组将保持本地select()方法结果,
         * 每个数组的第一个元素是所选套接字的数量(int)
         * 其他元素是所选套接字的文件描述符(int 文件描述符即系统分配的索引)
         */
        private final int[] readFds = new int[MAX_SELECTABLE_FDS + 1];
        private final int[] writeFds = new int[MAX_SELECTABLE_FDS + 1];
        private final int[] exceptFds = new int[MAX_SELECTABLE_FDS + 1];

        private SubSelector() {
            this.pollArrayIndex = 0; // main thread
        }

        /**
         * @param threadIndex helper threads  辅助线程数.
         */
        private SubSelector(int threadIndex) {
            this.pollArrayIndex = (threadIndex + 1) * MAX_SELECTABLE_FDS;
        }

        /**
         * poll for the main thread .
         * 主线程轮询.
         *
         * @return .
         * @throws IOException .
         */
        private int poll() throws IOException {
            return poll0(pollWrapper.pollArrayAddress,
                    Math.min(totalChannels, MAX_SELECTABLE_FDS),
                    readFds, writeFds, exceptFds, timeout);
        }

        /**
         * poll for helper threads
         * 辅助线程轮询.
         *
         * @param index .
         * @return .
         * @throws IOException
         */
        private int poll(int index) throws IOException {
            return poll0(pollWrapper.pollArrayAddress +
                            (pollArrayIndex * PollArrayWrapper.SIZE_POLLFD),
                    Math.min(MAX_SELECTABLE_FDS,
                            totalChannels - (index + 1) * MAX_SELECTABLE_FDS),
                    readFds, writeFds, exceptFds, timeout);
        }

        private native int poll0(long pollAddress, int numfds,
                                 int[] readFds, int[] writeFds, int[] exceptFds, long timeout);

        private int processSelectedKeys(long updateCount) {
            int numKeysUpdated = 0;
            numKeysUpdated += processFDSet(updateCount, readFds,
                    Net.POLLIN,
                    false);
            numKeysUpdated += processFDSet(updateCount, writeFds,
                    Net.POLLCONN |
                            Net.POLLOUT,
                    false);
            numKeysUpdated += processFDSet(updateCount, exceptFds,
                    Net.POLLIN |
                            Net.POLLCONN |
                            Net.POLLOUT,
                    true);
            return numKeysUpdated;
        }

        /**
         * Note, clearedCount is used to determine if the readyOps have been reset in this select operation.
         * 注,clearedCount用来决定在选择操作中readOps是否已被重置.
         * updateCount is used to tell if a key has been counted as updated in this select operation.
         * updateCount用于判断在此选择操作中是否将key算为已更新
         * <p>
         * me.updateCount <= me.clearedCount <= updateCount
         */
        private int processFDSet(long updateCount, int[] fds, int rOps,
                                 boolean isExceptFds) {
            int numKeysUpdated = 0;
            for (int i = 1; i <= fds[0]; i++) {
                int desc = fds[i];
                if (desc == wakeupSourceFd) {
                    synchronized (interruptLock) {
                        interruptTriggered = true;
                    }
                    continue;
                }
                MapEntry me = fdMap.get(desc);
                /* If me is null, the key was deregistered in the previous processDeregisterQueue.
                   若me是null,key已经在上次processDeregisterQueue()调用中注销
                 */
                if (me == null) {
                    continue;
                }
                SelectionKeyImpl sk = me.ski;

                /* The descriptor may be in the exceptfds set because there is OOB data queued to the socket.
                 *  If there is OOB data then it is discarded and the key is not added to the selected set.
                 * 描述符可能在exceptfds集中，因为有OOB数据入队到套接字
                 * 如果有OOB数据，那么它将被丢弃，并且键不会被添加到所选的集合中
                 */
                if (isExceptFds &&
                        (sk.channel() instanceof SocketChannelImpl) &&
                        discardUrgentData(desc)) {
                    continue;
                }
                // Key in selected set
                if (selectedKeys.contains(sk)) {
                    if (me.clearedCount != updateCount) {
                        if (sk.channel.translateAndSetReadyOps(rOps, sk) &&
                                (me.updateCount != updateCount)) {
                            me.updateCount = updateCount;
                            numKeysUpdated++;
                        }
                    } else { // The readyOps have been set; now add
                        if (sk.channel.translateAndUpdateReadyOps(rOps, sk) &&
                                (me.updateCount != updateCount)) {
                            me.updateCount = updateCount;
                            numKeysUpdated++;
                        }
                    }
                    me.clearedCount = updateCount;
                } else { // Key is not in selected set yet
                    if (me.clearedCount != updateCount) {
                        sk.channel.translateAndSetReadyOps(rOps, sk);
                        if ((sk.nioReadyOps() & sk.nioInterestOps()) != 0) {
                            selectedKeys.add(sk);
                            me.updateCount = updateCount;
                            numKeysUpdated++;
                        }
                    } else { // The readyOps have been set; now add
                        sk.channel.translateAndUpdateReadyOps(rOps, sk);
                        if ((sk.nioReadyOps() & sk.nioInterestOps()) != 0) {
                            selectedKeys.add(sk);
                            me.updateCount = updateCount;
                            numKeysUpdated++;
                        }
                    }
                    me.clearedCount = updateCount;
                }
            }
            return numKeysUpdated;
        }
    }

    /**
     * Represents a helper thread used for select.
     * 代表用于select的辅助线程
     */
    private final class SelectThread extends Thread {
        /**
         * index of this thread
         * 线程索引
         */
        private final int index;
        final SubSelector subSelector;
        /**
         * last run number
         * 最后的运行数量.
         */
        private long lastRun = 0;
        private volatile boolean zombie;

        // Creates a new thread
        private SelectThread(int i) {
            this.index = i;
            this.subSelector = new SubSelector(i);
            /*  make sure we wait for next round of poll
             *  确保我们等待下一圈轮询
             */
            this.lastRun = startLock.runsCounter;
        }

        void makeZombie() {
            zombie = true;
        }

        boolean isZombie() {
            return zombie;
        }

        @Override
        public void run() {
            /*
             * poll loop
             * 轮询循环
             */
            while (true) {
                /*
                 * wait for the start of poll. If this thread has become  redundant, then exit.
                 * 等待轮询开始.如果该线程多余,直接退出.
                 */
                if (startLock.waitForStart(this)) {
                    return;
                }
                /* call poll()
                   调用 poll()方法.
                 */
                try {
                    subSelector.poll(index);
                } catch (IOException e) {
                    /* Save this exception and let other threads finish.
                       保存异常,让其它线程结束.
                     */
                    finishLock.setException(e);
                }
                /* notify main thread, that this thread has finished, and wakeup others, if this thread is the first to finish.
                   通知主线程,该线程已经结束,如果该线程是第一个结束,唤醒其它线程
                 */
                finishLock.threadFinished();
            }
        }
    }

    /**
     * After some channels registered/deregistered, the number of required helper threads may have changed. Adjust this number.
     * 在一些channel注册或注销后,所需要的帮助线程数发送变化,调整数量.
     */
    private void adjustThreadsCount() {
        if (threadsCount > threads.size()) {
            /* More threads needed. Start more threads.
             * 需要更多线程,开始更多线程.
             */
            for (int i = threads.size(); i < threadsCount; i++) {
                SelectThread newThread = new SelectThread(i);
                threads.add(newThread);
                newThread.setDaemon(true);
                newThread.start();
            }
        } else if (threadsCount < threads.size()) {
            /* Some threads become redundant. Remove them from the threads List.
               一些线程变得多余,从线程集合中移除.
             */
            for (int i = threads.size() - 1; i >= threadsCount; i--) {
                threads.remove(i).makeZombie();
            }
        }
    }

    /**
     * Sets Windows wakeup socket to a signaled state.
     * 设置 windows 唤醒套接字设为信号状态.
     */
    private void setWakeupSocket() {
        setWakeupSocket0(wakeupSinkFd);
    }

    private native void setWakeupSocket0(int wakeupSinkFd);

    /**
     * Sets Windows wakeup socket to a non-signaled state.
     * 设置 windows 唤醒套接字设为无信号状态.
     */
    private void resetWakeupSocket() {
        synchronized (interruptLock) {
            if (!interruptTriggered) {
                return;
            }
            resetWakeupSocket0(wakeupSourceFd);
            interruptTriggered = false;
        }
    }

    private native void resetWakeupSocket0(int wakeupSourceFd);

    private native boolean discardUrgentData(int fd);

    /**
     * We increment this counter on each call to updateSelectedKeys() each entry in  SubSelector.
     * 我们在每次调用updateSelectedKeys()方法中SubSelector的每个条目后增加计数器.
     * fdsMap has a memorized value of updateCount.
     * fdsMap 有一个updateCount的记忆功能
     * When we increment numKeysUpdated we set updateCount for the corresponding entry to its current value.
     * 当我们增加numKeysUpdated,我们为对应条目设置为它的当前值updateCount
     * This is used to avoid counting the same key more than once - the same key can appear in readfds and writefds.
     * 为了了用来避免为相同key计数多次-相同的键可以出现在readfds和writefds中。
     */
    private long updateCount = 0;

    /**
     * Update ops of the corresponding Channels. Add the ready keys to the ready queue.
     * 更新相关channel的操作,向ready队列中添加ready key
     *
     * @return .
     */
    private int updateSelectedKeys() {
        updateCount++;
        int numKeysUpdated = 0;
        numKeysUpdated += subSelector.processSelectedKeys(updateCount);
        for (SelectThread t : threads) {
            numKeysUpdated += t.subSelector.processSelectedKeys(updateCount);
        }
        return numKeysUpdated;
    }

    @Override
    protected void implClose() throws IOException {
        synchronized (closeLock) {
            if (channelArray != null) {
                if (pollWrapper != null) {
                    // prevent further wakeup 防止进一步唤醒.
                    synchronized (interruptLock) {
                        interruptTriggered = true;
                    }
                    wakeupPipe.sink().close();
                    wakeupPipe.source().close();
                    // Deregister channels 注销channel
                    for (int i = 1; i < totalChannels; i++) {
                        // skip wakeupEvent 跳过唤醒事件.
                        if (i % MAX_SELECTABLE_FDS != 0) {
                            deregister(channelArray[i]);
                            SelectableChannel selch = channelArray[i].channel();
                            if (!selch.isOpen() && !selch.isRegistered()) {
                                ((SelChImpl) selch).kill();
                            }
                        }
                    }
                    pollWrapper.free();
                    pollWrapper = null;
                    selectedKeys = null;
                    channelArray = null;
                    /* Make all remaining helper threads exit
                     * 使所有辅助线程退出.
                     */
                    for (SelectThread t : threads) {
                        t.makeZombie();
                    }
                    startLock.startThreads();
                }
            }
        }
    }

    @Override
    protected void implRegister(SelectionKeyImpl ski) {
        synchronized (closeLock) {
            if (pollWrapper == null) {
                throw new ClosedSelectorException();
            }
            growIfNeeded();
            channelArray[totalChannels] = ski;
            ski.setIndex(totalChannels);
            fdMap.put(ski);
            keys.add(ski);
            pollWrapper.addEntry(totalChannels, ski);
            totalChannels++;
        }
    }

    private void growIfNeeded() {
        if (channelArray.length == totalChannels) {
            /* Make a larger array
             * 为数组扩容
             */
            int newSize = totalChannels * 2;
            SelectionKeyImpl temp[] = new SelectionKeyImpl[newSize];
            System.arraycopy(channelArray, 1, temp, 1, totalChannels - 1);
            channelArray = temp;
            pollWrapper.grow(newSize);
        }
        // more threads needed 需要更多线程
        if (totalChannels % MAX_SELECTABLE_FDS == 0) {
            pollWrapper.addWakeupSocket(wakeupSourceFd, totalChannels);
            totalChannels++;
            threadsCount++;
        }
    }

    @Override
    protected void implDereg(SelectionKeyImpl ski) throws IOException {
        int i = ski.getIndex();
        assert (i >= 0);
        synchronized (closeLock) {
            if (i != totalChannels - 1) {
                // Copy end one over it
                SelectionKeyImpl endChannel = channelArray[totalChannels - 1];
                channelArray[i] = endChannel;
                endChannel.setIndex(i);
                pollWrapper.replaceEntry(pollWrapper, totalChannels - 1,
                        pollWrapper, i);
            }
            ski.setIndex(-1);
        }
        channelArray[totalChannels - 1] = null;
        totalChannels--;
        if (totalChannels != 1 && totalChannels % MAX_SELECTABLE_FDS == 1) {
            totalChannels--;
            /* The last thread has become redundant.
             * 最后一个线程变得多余
             */
            threadsCount--;
        }
        /* Remove the key from fdMap, keys and selectedKeys
         * 从fdMap, keys and selectedKeys中移除key
         */
        fdMap.remove(ski);
        keys.remove(ski);
        selectedKeys.remove(ski);
        deregister(ski);
        SelectableChannel selch = ski.channel();
        if (!selch.isOpen() && !selch.isRegistered()) {
            ((SelChImpl) selch).kill();
        }
    }

    @Override
    public void putEventOps(SelectionKeyImpl sk, int ops) {
        synchronized (closeLock) {
            if (pollWrapper == null) {
                throw new ClosedSelectorException();
            }
            // make sure this sk has not been removed yet 确保该sk未移除
            int index = sk.getIndex();
            if (index == -1) {
                throw new CancelledKeyException();
            }
            pollWrapper.putEventOps(index, ops);
        }
    }

    @Override
    public Selector wakeup() {
        synchronized (interruptLock) {
            if (!interruptTriggered) {
                setWakeupSocket();
                interruptTriggered = true;
            }
        }
        return this;
    }

    static {
        IOUtil.load();
    }
}
