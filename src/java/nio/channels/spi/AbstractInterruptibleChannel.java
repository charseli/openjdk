/*
 * Copyright (c) 2000, 2013, Oracle and/or its affiliates. All rights reserved.
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

package java.nio.channels.spi;

import sun.nio.ch.Interruptible;

import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.Channel;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.InterruptibleChannel;


/**
 * Base implementation class for interruptible channels.
 * 可中断channel的基础实现类.
 *
 * <p> This class encapsulates the low-level machinery required to implement
 * the asynchronous closing and interruption of channels.  A concrete channel
 * class must invoke the {@link #begin begin} and {@link #end end} methods
 * before and after, respectively, invoking an I/O operation that might block
 * indefinitely.  In order to ensure that the {@link #end end} method is always
 * invoked, these methods should be used within a
 * <tt>try</tt>&nbsp;...&nbsp;<tt>finally</tt> block:
 * <p>该类封装了实现通道异步关闭和中断所需的底层机制.具体的channel类必须分别在调用I/O操作之前和之后调用begin和end方法，该操作可能会无限期阻塞。
 * 为了确保总是调用end方法，应该在try…finally块:</p>
 *
 * <blockquote><pre>
 * boolean completed = false;
 * try {
 *     begin();
 *     completed = ...;    // Perform blocking I/O operation
 *     return ...;         // Return result
 * } finally {
 *     end(completed);
 * }</pre></blockquote>
 *
 * <p> The <tt>completed</tt> argument to the {@link #end end} method tells
 * whether or not the I/O operation actually completed, that is, whether it had
 * any effect that would be visible to the invoker.  In the case of an
 * operation that reads bytes, for example, this argument should be
 * <tt>true</tt> if, and only if, some bytes were actually transferred into the
 * invoker's target buffer.
 * <p>end()方法的completed参数告诉I/O操作是否实际完成，也就是说，它是否具有任何效果对调用者是可见的。
 * 例如，在读取字节的操作中，当且仅当某些字节实际传输到调用程序的目标缓冲区时，这个参数应该为true</p>
 *
 * <p> A concrete channel class must also implement the {@link
 * #implCloseChannel implCloseChannel} method in such a way that if it is
 * invoked while another thread is blocked in a native I/O operation upon the
 * channel then that operation will immediately return, either by throwing an
 * exception or by returning normally.  If a thread is interrupted or the
 * channel upon which it is blocked is asynchronously closed then the channel's
 * {@link #end end} method will throw the appropriate exception.
 *<p>具体的通道类还必须实现implCloseChannel()方法，如果在通道上的本机I/O操作中阻塞另一个线程时调用了它，
 * 那么该操作将立即返回，通过抛出异常或正常返回。如果线程被中断,或者阻塞的通道被异步关闭，那么通道的end()方法将抛出适当的异常</p>
 *
 * <p> This class performs the synchronization required to implement the {@link
 * java.nio.channels.Channel} specification.  Implementations of the {@link
 * #implCloseChannel implCloseChannel} method need not synchronize against
 * other threads that might be attempting to close the channel.  </p>
 *<p>该类执行实现通道规范所需的同步。implCloseChannel()方法的实现不需要与可能试图关闭通道的其他线程同步</p>
 *
 * @author Mark Reinhold
 * @author JSR-51 Expert Group
 * @since 1.4
 */

public abstract class AbstractInterruptibleChannel
        implements Channel, InterruptibleChannel {

    private final Object closeLock = new Object();
    private volatile boolean open = true;

    /**
     * Initializes a new instance of this class.
     */
    protected AbstractInterruptibleChannel() {
    }

    /**
     * Closes this channel.
     *
     * <p> If the channel has already been closed then this method returns
     * immediately.  Otherwise it marks the channel as closed and then invokes
     * the {@link #implCloseChannel implCloseChannel} method in order to
     * complete the close operation.  </p>
     *
     * @throws IOException If an I/O error occurs
     */
    @Override
    public final void close() throws IOException {
        synchronized (closeLock) {
            if (!open) {
                return;
            }
            open = false;
            implCloseChannel();
        }
    }

    /**
     * Closes this channel.
     *
     * <p> This method is invoked by the {@link #close close} method in order
     * to perform the actual work of closing the channel.  This method is only
     * invoked if the channel has not yet been closed, and it is never invoked
     * more than once.
     * <p>此方法由close方法调用，以便执行关闭通道的实际工作。只有在通道尚未关闭时才调用此方法，并且从未调用超过一次</p>
     *
     * <p> An implementation of this method must arrange for any other thread
     * that is blocked in an I/O operation upon this channel to return
     * immediately, either by throwing an exception or by returning normally.
     * <p>此方法的实现必须通过抛出异常或正常返回，来安排在此通道上的I/O操作中阻塞的任何其他线程立即返回</p>
     * </p>
     *
     * @throws IOException If an I/O error occurs while closing the channel
     */
    protected abstract void implCloseChannel() throws IOException;

    @Override
    public final boolean isOpen() {
        return open;
    }


    // -- Interruption machinery --

    private Interruptible interruptor;
    private volatile Thread interrupted;

    /**
     * Marks the beginning of an I/O operation that might block indefinitely.
     *
     * <p> This method should be invoked in tandem with the {@link #end end}
     * method, using a <tt>try</tt>&nbsp;...&nbsp;<tt>finally</tt> block as
     * shown <a href="#be">above</a>, in order to implement asynchronous
     * closing and interruption for this channel.  </p>
     *
     * <p>标志可能无限期阻塞的I/O操作的开始。
     * 此方法应该与end方法一起调用，使用try…最后如图所示阻塞，以实现对该通道的异步关闭和中断</p>
     */
    protected final void begin() {
        if (interruptor == null) {
            interruptor = new Interruptible() {
                @Override
                public void interrupt(Thread target) {
                    synchronized (closeLock) {
                        if (!open) {
                            return;
                        }
                        open = false;
                        interrupted = target;
                        try {
                            AbstractInterruptibleChannel.this.implCloseChannel();
                        } catch (IOException x) {
                        }
                    }
                }
            };
        }
        blockedOn(interruptor);
        Thread me = Thread.currentThread();
        if (me.isInterrupted()) {
            interruptor.interrupt(me);
        }
    }

    /**
     * Marks the end of an I/O operation that might block indefinitely.
     *
     * <p> This method should be invoked in tandem with the {@link #begin
     * begin} method, using a <tt>try</tt>&nbsp;...&nbsp;<tt>finally</tt> block
     * as shown <a href="#be">above</a>, in order to implement asynchronous
     * closing and interruption for this channel.  </p>
     *
     * @param completed <tt>true</tt> if, and only if, the I/O operation completed
     *                  successfully, that is, had some effect that would be visible to
     *                  the operation's invoker
     * @throws AsynchronousCloseException If the channel was asynchronously closed
     * @throws ClosedByInterruptException If the thread blocked in the I/O operation was interrupted
     */
    protected final void end(boolean completed)
            throws AsynchronousCloseException {
        blockedOn(null);
        Thread interrupted = this.interrupted;
        if (interrupted != null && interrupted == Thread.currentThread()) {
            interrupted = null;
            throw new ClosedByInterruptException();
        }
        if (!completed && !open) {
            throw new AsynchronousCloseException();
        }
    }


    // -- sun.misc.SharedSecrets --
    static void blockedOn(Interruptible intr) {         // package-private
        sun.misc.SharedSecrets.getJavaLangAccess().blockedOn(Thread.currentThread(),
                intr);
    }
}
