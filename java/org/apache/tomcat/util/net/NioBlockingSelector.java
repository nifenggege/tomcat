/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.util.net;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.collections.SynchronizedQueue;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.net.NioEndpoint.NioSocketWrapper;
import org.apache.tomcat.util.res.StringManager;

public class NioBlockingSelector {

    private static final Log log = LogFactory.getLog(NioBlockingSelector.class);
    protected static final StringManager sm = StringManager.getManager(NioBlockingSelector.class);
    private static final AtomicInteger threadCounter = new AtomicInteger(); //用于给poller name，代表有多少个poller

    //成员属性
    private final SynchronizedStack<KeyReference> keyReferenceStack =
            new SynchronizedStack<>(); //对象池
    protected Selector sharedSelector; //selector
    protected BlockPoller poller; //blockPoller

    public NioBlockingSelector() {

    }

    public void open(Selector selector) {//传入selector， 通过该selector构造BlockingPoller， 启动poller线程
        sharedSelector = selector;
        poller = new BlockPoller();
        poller.selector = sharedSelector;
        poller.setDaemon(true);
        poller.setName("NioBlockingSelector.BlockPoller-" + threadCounter.incrementAndGet());
        poller.start();
    }

    public void close() { //关闭poller
        if (poller!=null) {
            poller.disable();
            poller.interrupt();
            poller = null;
        }
    }

    /**
     * Performs a blocking write using the bytebuffer for data to be written
     * If the <code>selector</code> parameter is null, then it will perform a busy write that could
     * take up a lot of CPU cycles.
     * @param buf ByteBuffer - the buffer containing the data, we will write as long as <code>(buf.hasRemaining()==true)</code>
     * @param socket SocketChannel - the socket to write data to
     * @param writeTimeout long - the timeout for this write operation in milliseconds, -1 means no timeout
     * @return int - returns the number of bytes written
     * @throws EOFException if write returns -1
     * @throws SocketTimeoutException if the write times out
     * @throws IOException if an IO Exception occurs in the underlying socket logic
     */
    public int write(ByteBuffer buf, NioChannel socket, long writeTimeout) //返回write的数据量
            throws IOException {

        //获取socket在之前poller的selector上注册的事件
        SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());
        if (key == null) {
            throw new IOException(sm.getString("nioBlockingSelector.keyNotRegistered"));
        }
        KeyReference reference = keyReferenceStack.pop();  //reference的key是在add任务中赋值的，KeyReference主要是为了释放key
        if (reference == null) {
            reference = new KeyReference();
        }
        NioSocketWrapper att = (NioSocketWrapper) key.attachment();
        int written = 0;
        boolean timedout = false;
        int keycount = 1; //assume we can write
        long time = System.currentTimeMillis(); //start the timeout timer
        try {
            while ( (!timedout) && buf.hasRemaining()) { //还有可写内容，且没有写超时
                if (keycount > 0) { //only write if we were registered for a write
                    int cnt = socket.write(buf); //往socket中写数据，如果返回-1表示socket关闭，0表示socket目前不可写
                    if (cnt == -1)
                        throw new EOFException();
                    written += cnt; //已经写的字节数
                    if (cnt > 0) {
                        time = System.currentTimeMillis(); //reset our timeout timer
                        continue; //we successfully wrote, try again without a selector
                    }
                }

                //到这里相当于， 目前socket不可写，但是仍然存在需要写的数据
                try {
                    if ( att.getWriteLatch()==null || att.getWriteLatch().getCount()==0) att.startWriteLatch(1); //写控制为1
                    //往自己的poller中添加任务，等待执行，添加写任务
                    poller.add(att,SelectionKey.OP_WRITE,reference);  //Poller 只是判断是否可写的状态，并通过att的writeLatch进行控制
                    att.awaitWriteLatch(AbstractEndpoint.toTimeout(writeTimeout),TimeUnit.MILLISECONDS);
                    //等待执行完成，如果还没有执行完成，那么就应该是超时了
                } catch (InterruptedException ignore) {
                    // Ignore
                }

                //两种情况 1，超时， 2.可写
                if ( att.getWriteLatch()!=null && att.getWriteLatch().getCount()> 0) {
                    //we got interrupted, but we haven't received notification from the poller.
                    //说明超时了已经
                    keycount = 0;
                }else {
                    //latch countdown has happened
                    //说明socket状态可写
                    keycount = 1;
                    att.resetWriteLatch();
                }

                if (writeTimeout > 0 && (keycount == 0))  //到这里讲道理就超时了，应为上面是等待了writeTimeout时间
                    timedout = (System.currentTimeMillis() - time) >= writeTimeout;
            } //while
            if (timedout)
                throw new SocketTimeoutException();
        } finally {
            //将OP_WRITE 从poller中移除，只是移除当前事件
            poller.remove(att,SelectionKey.OP_WRITE);
            if (timedout && reference.key!=null) {
                poller.cancelKey(reference.key);
            }
            reference.key = null;
            keyReferenceStack.push(reference);
        }
        return written;
    }

    /**
     * Performs a blocking read using the bytebuffer for data to be read
     * If the <code>selector</code> parameter is null, then it will perform a busy read that could
     * take up a lot of CPU cycles.
     * @param buf ByteBuffer - the buffer containing the data, we will read as until we have read at least one byte or we timed out
     * @param socket SocketChannel - the socket to write data to
     * @param readTimeout long - the timeout for this read operation in milliseconds, -1 means no timeout
     * @return int - returns the number of bytes read
     * @throws EOFException if read returns -1
     * @throws SocketTimeoutException if the read times out
     * @throws IOException if an IO Exception occurs in the underlying socket logic
     */
    public int read(ByteBuffer buf, NioChannel socket, long readTimeout) throws IOException { //读到内容就会返回
        SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());
        if (key == null) {
            throw new IOException(sm.getString("nioBlockingSelector.keyNotRegistered"));
        }
        KeyReference reference = keyReferenceStack.pop();
        if (reference == null) {
            reference = new KeyReference();
        }
        NioSocketWrapper att = (NioSocketWrapper) key.attachment();
        int read = 0;
        boolean timedout = false;
        int keycount = 1; //assume we can read
        long time = System.currentTimeMillis(); //start the timeout timer
        try {
            while(!timedout) {
                if (keycount > 0) { //only read if we were registered for a read
                    read = socket.read(buf);
                    if (read != 0) {//不为0存在两种情况，>0读取到内容，-1连接关闭
                        break;
                    }
                }

                //read==0
                try {
                    if ( att.getReadLatch()==null || att.getReadLatch().getCount()==0) att.startReadLatch(1);
                    poller.add(att,SelectionKey.OP_READ, reference);
                    att.awaitReadLatch(AbstractEndpoint.toTimeout(readTimeout), TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignore) {
                    // Ignore
                }
                if ( att.getReadLatch()!=null && att.getReadLatch().getCount()> 0) {
                    //we got interrupted, but we haven't received notification from the poller.
                    //还没有读取到内容，已经超时
                    keycount = 0;
                }else {
                    //latch countdown has happened
                    //可以读了
                    keycount = 1;
                    att.resetReadLatch();
                }
                if (readTimeout >= 0 && (keycount == 0))
                    timedout = (System.currentTimeMillis() - time) >= readTimeout;
            } //while
            if (timedout)
                throw new SocketTimeoutException();
        } finally {
            poller.remove(att,SelectionKey.OP_READ);
            if (timedout && reference.key!=null) {
                poller.cancelKey(reference.key);
            }
            reference.key = null;
            keyReferenceStack.push(reference);
        }
        return read;
    }


    protected static class BlockPoller extends Thread {
        protected volatile boolean run = true;
        protected Selector selector = null;
        protected final SynchronizedQueue<Runnable> events = new SynchronizedQueue<>();
        public void disable() { run = false; selector.wakeup();}
        protected final AtomicInteger wakeupCounter = new AtomicInteger(0);

        public void cancelKey(final SelectionKey key) { //取消key在selector上的注册
            Runnable r = new RunnableCancel(key);
            events.offer(r);
            wakeup();
        }

        public void wakeup() {
            if (wakeupCounter.addAndGet(1)==0) selector.wakeup();
        }

        public void cancel(SelectionKey sk, NioSocketWrapper key, int ops){ //取消sk的事件， 清除attach，事件计数-1
            if (sk!=null) {
                sk.cancel();
                sk.attach(null);
                if (SelectionKey.OP_WRITE==(ops&SelectionKey.OP_WRITE)) countDown(key.getWriteLatch());
                if (SelectionKey.OP_READ==(ops&SelectionKey.OP_READ))countDown(key.getReadLatch());
            }
        }

        public void add(final NioSocketWrapper key, final int ops, final KeyReference ref) {
            if ( key == null ) return;
            NioChannel nch = key.getSocket();
            final SocketChannel ch = nch.getIOChannel();
            if ( ch == null ) return;

            Runnable r = new RunnableAdd(ch, key, ops, ref); //将事件注册到Poller的selector上
            events.offer(r);
            wakeup();
        }

        public void remove(final NioSocketWrapper key, final int ops) {
            if ( key == null ) return;
            NioChannel nch = key.getSocket();
            final SocketChannel ch = nch.getIOChannel();
            if ( ch == null ) return;

            Runnable r = new RunnableRemove(ch, key, ops); //取消指定的事件
            events.offer(r);
            wakeup();
        }

        public boolean events() {
            Runnable r = null;

            /* We only poll and run the runnable events when we start this
             * method. Further events added to the queue later will be delayed
             * to the next execution of this method.
             *
             * We do in this way, because running event from the events queue
             * may lead the working thread to add more events to the queue (for
             * example, the worker thread may add another RunnableAdd event when
             * waken up by a previous RunnableAdd event who got an invalid
             * SelectionKey). Trying to consume all the events in an increasing
             * queue till it's empty, will make the loop hard to be terminated,
             * which will kill a lot of time, and greatly affect performance of
             * the poller loop.
             */
            int size = events.size();
            for (int i = 0; i < size && (r = events.poll()) != null; i++) {
                r.run();
            }

            return (size > 0);
        }

        @Override
        public void run() {  //执行队列的任务，并控制计数
            while (run) {
                try {
                    events(); //执行events队列中的任务
                    int keyCount = 0;
                    try {
                        int i = wakeupCounter.get();
                        if (i>0)
                            keyCount = selector.selectNow();
                        else {
                            wakeupCounter.set(-1);
                            keyCount = selector.select(1000);
                        }
                        wakeupCounter.set(0);
                        if (!run) break;
                    }catch ( NullPointerException x ) {
                        //sun bug 5076772 on windows JDK 1.5
                        if (selector==null) throw x;
                        if ( log.isDebugEnabled() ) log.debug("Possibly encountered sun bug 5076772 on windows JDK 1.5",x);
                        continue;
                    } catch ( CancelledKeyException x ) {
                        //sun bug 5076772 on windows JDK 1.5
                        if ( log.isDebugEnabled() ) log.debug("Possibly encountered sun bug 5076772 on windows JDK 1.5",x);
                        continue;
                    } catch (Throwable x) {
                        ExceptionUtils.handleThrowable(x);
                        log.error(sm.getString("nioBlockingSelector.selectError"), x);
                        continue;
                    }

                    Iterator<SelectionKey> iterator = keyCount > 0 ? selector.selectedKeys().iterator() : null;

                    // Walk through the collection of ready keys and dispatch
                    // any active event.
                    while (run && iterator != null && iterator.hasNext()) {
                        SelectionKey sk = iterator.next();
                        NioSocketWrapper attachment = (NioSocketWrapper)sk.attachment();
                        try {
                            iterator.remove();
                            sk.interestOps(sk.interestOps() & (~sk.readyOps()));
                            if ( sk.isReadable() ) {
                                countDown(attachment.getReadLatch());
                            }
                            if (sk.isWritable()) {
                                countDown(attachment.getWriteLatch());
                            }
                        }catch (CancelledKeyException ckx) {
                            sk.cancel();
                            countDown(attachment.getReadLatch());
                            countDown(attachment.getWriteLatch());
                        }
                    }//while
                }catch ( Throwable t ) {
                    log.error(sm.getString("nioBlockingSelector.processingError"), t);
                }
            }
            events.clear();
            // If using a shared selector, the NioSelectorPool will also try and
            // close the selector. Try and avoid the ClosedSelectorException
            // although because multiple threads are involved there is always
            // the possibility of an Exception here.
            if (selector.isOpen()) {
                try {
                    // Cancels all remaining keys
                    selector.selectNow();
                }catch( Exception ignore ) {
                    if (log.isDebugEnabled())log.debug("",ignore);
                }
            }
            try {
                selector.close();
            }catch( Exception ignore ) {
                if (log.isDebugEnabled())log.debug("",ignore);
            }
        }

        public void countDown(CountDownLatch latch) {
            if ( latch == null ) return;
            latch.countDown();
        }


        private class RunnableAdd implements Runnable {

            private final SocketChannel ch;
            private final NioSocketWrapper key;
            private final int ops;
            private final KeyReference ref;

            public RunnableAdd(SocketChannel ch, NioSocketWrapper key, int ops, KeyReference ref) {
                this.ch = ch;
                this.key = key;
                this.ops = ops;
                this.ref = ref;
            }

            @Override
            public void run() {
                SelectionKey sk = ch.keyFor(selector);
                try {
                    if (sk == null) {
                        sk = ch.register(selector, ops, key);
                        ref.key = sk;
                    } else if (!sk.isValid()) {
                        cancel(sk, key, ops);
                    } else {
                        sk.interestOps(sk.interestOps() | ops);
                    }
                } catch (CancelledKeyException cx) {
                    cancel(sk, key, ops);
                } catch (ClosedChannelException cx) {
                    cancel(null, key, ops);
                }
            }
        }


        private class RunnableRemove implements Runnable {

            private final SocketChannel ch;
            private final NioSocketWrapper key;
            private final int ops;

            public RunnableRemove(SocketChannel ch, NioSocketWrapper key, int ops) {
                this.ch = ch;
                this.key = key;
                this.ops = ops;
            }

            @Override
            public void run() {
                SelectionKey sk = ch.keyFor(selector);
                try {
                    if (sk == null) {
                        if (SelectionKey.OP_WRITE==(ops&SelectionKey.OP_WRITE)) countDown(key.getWriteLatch());
                        if (SelectionKey.OP_READ==(ops&SelectionKey.OP_READ))countDown(key.getReadLatch());
                    } else {
                        if (sk.isValid()) {
                            sk.interestOps(sk.interestOps() & (~ops));
                            if (SelectionKey.OP_WRITE==(ops&SelectionKey.OP_WRITE)) countDown(key.getWriteLatch());
                            if (SelectionKey.OP_READ==(ops&SelectionKey.OP_READ))countDown(key.getReadLatch());
                            if (sk.interestOps()==0) {
                                sk.cancel();
                                sk.attach(null);
                            }
                        }else {
                            sk.cancel();
                            sk.attach(null);
                        }
                    }
                }catch (CancelledKeyException cx) {
                    if (sk!=null) {
                        sk.cancel();
                        sk.attach(null);
                    }
                }
            }

        }


        public static class RunnableCancel implements Runnable {

            private final SelectionKey key;

            public RunnableCancel(SelectionKey key) {
                this.key = key;
            }

            @Override
            public void run() {
                key.cancel();
            }
        }
    }


    public static class KeyReference {
        SelectionKey key = null;

        @Override
        protected void finalize() {
            if (key!=null && key.isValid()) {
                log.warn(sm.getString("nioBlockingSelector.possibleLeak"));
                try {key.cancel();}catch (Exception ignore){}
            }
        }
    }
}
