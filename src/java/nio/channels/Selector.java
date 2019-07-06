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

package java.nio.channels;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.spi.SelectorProvider;
import java.util.Set;


/**
 * A multiplexor of {@link SelectableChannel} objects.
 * <p>SelectableChannel对象的多路复用器</p>
 *
 * <p> A selector may be created by invoking the {@link #open open} method of this class,which will use the system's default {@link java.nio.channels.spi.SelectorProvider selector provider} to create a new selector.</p>
 * <p>通过调用该类的open()方法可创建selector对象,该方法使用系统默认的SelectorProvider创建新的selector</p>
 *
 * <p>A selector may also be created by invoking the {@link java.nio.channels.spi.SelectorProvider#openSelector openSelector} method of a custom selector provider.
 * <p>通过调用一个自定义的SelectorProvider的openSelector方法创建selector</p>
 *
 * <p>A selector remains open until it is closed via its {@link #close close} method.</p>
 * <p>selector保持打开直到调其close()方法才关闭</p>
 *
 * <p>A selectable channel's registration with a selector is represented by a{@link SelectionKey} object.</p>
 * <p>用selector注册的selectable channel由SelectionKey对象表示</p>
 *
 * <p>A selector maintains three sets of selection keys:</p>
 * <p>selector维护3个键集</p>
 *
 * <ul><li><p> The <i>key set</i> contains the keys representing the current channel registrations of this selector.This set is returned by the {@link #keys() keys} method. </p></li>
 * <p>第一个键集包含的键,代表该selector的当前通道的注册.该集合通过key()方法返回.</p>
 *
 *
 * <li><p> The <i>selected-key set</i> is the set of keys such that each key's channel was detected to be ready for at least one of the operations identified in the key's interest set during a prior selection operation.
 * <p>selected-key是键集,在预先选择操作期间,每个key对应的channel被侦测到至少为一个可在key的兴趣集中识别的操作做好准备.</p>
 * This set is returned by the {@link #selectedKeys() selectedKeys} method.The selected-key set is always a subset of the key set. </p>
 * <p>该集合通过selectedKeys()方法返回.selected-key集合通常为key集合子集</p>
 * </li>
 *
 * <li><p> The <i>cancelled-key</i> set is the set of keys that have been cancelled but whose channels have not yet been deregistered.  This set is not directly accessible.The cancelled-key set is always a subset of the key set. </p></li>
 * <p>cancelled-key是已被取消的键集,但其对应的channel还未被注销.该集合无法直接获取.</p></ul>
 *
 * <p> All three sets are empty in a newly-created selector.</p>
 * <p>三个集合在新建selector时为空</p>
 *
 * <p> A key is added to a selector's key set as a side effect of registering a channel via the channel's {@link SelectableChannel#register(Selector, int) register} method.
 * Cancelled keys are removed from the key set during selection operations.  The key set itself is not directly modifiable.
 * <p>key被添加到selector键集中作为通过channel的register()方法注册该channel的副作用，选择操作过程中取消的key将从键集中移除.键集自身无法直接修改.</p>
 *
 * <p> A key is added to its selector's cancelled-key set when it is cancelled,whether by closing its channel or by invoking its {@link SelectionKey#cancel cancel} method.
 * <p>key被添加到其selector对象的cancelled-key集合中,当它被取消,不管时关闭对应的channel或执行SelectionKey的cancel()方法</p>
 * Cancelling a key will cause its channel to be deregistered during the next selection operation, at which time the key will removed from all of the selector's key sets.
 * <p>取消key将导致其对应channel在下次选择操作期间被注销,那时key将从selector的所有键集中移除</p>
 *
 * <a name="sks"></a><p> Keys are added to the selected-key set by selection operations.
 * A key may be removed directly from the selected-key set by invoking the set's {@link java.util.Set#remove(java.lang.Object) remove} method or by invoking the {@link java.util.Iterator#remove() remove} method
 * of an {@link java.util.Iterator iterator} obtained from the set.Keys are never removed from the selected-key set in any other way;
 * they are not, in particular, removed as a side effect of selection operations.  Keys may not be added directly to the selected-key set. </p>
 * <p>key通过选择操作添加到selected-key集合中.key可以通过执行set的remove()方法或从set获取的Iterator的remove()方法直接从selected-key集合中移除.无其它方法;特别是不会作为选择操作的副作用.key不可直接添加到selected-key集合中</p>
 *
 * <a name="selop"></a>
 * <h2>Selection</h2>
 * <p>选择</p>
 *
 * <p> During each selection operation, keys may be added to and removed from a selector's selected-key set and may be removed from its key and cancelled-key sets.  Selection is performed by the {@link #select()}, {@link
 * #select(long)}, and {@link #selectNow()} methods, and involves three steps:</p>
 * <p>在选择操作期间,key可能通过select()、selectNow()方法从selector的selected-ky集合中添加或移除;从键集或cancelled-key集合中移除,涉及三个步骤</p>
 *
 * <ol>
 *
 * <li><p> Each key in the cancelled-key set is removed from each key set of which it is a member, and its channel is deregistered.This step leaves the cancelled-key set empty. </p></li>
 * <p>每个在cancelled-key集合中的key将被从包含它的键的集中移除,其对应的channel被注销.这一步后cancelled-key集合为空</p>
 *
 * <li><p> The underlying operating system is queried for an update as to the readiness of each remaining channel to perform any of the operations identified by its key's interest set as of the moment that the selection operation began.
 * For a channel that is ready for at least one such operation, one of the following two actions is performed: </p>
 * <p>查询底层操作系统,从选择操作开始那刻开始,执行任何被对应key的兴趣集识别的操作,为每个剩余的准备好channel做更新.对于准备好进行至少一次操作的channel,如下操作之一会被执行：</p>
 *
 * <ol> <li><p> If the channel's key is not already in the selected-key set then it is added to that set and its ready-operation set is modified to identify exactly those operations for which the channel is now reported to be ready.
 * Any readiness information previously recorded in the ready set is discarded.  </p>
 * 若channel对应的ky不在selected-key集合中,添加它;为准确识别这些channel上报的已准备好的操作,该channel的ready-operation集合将被修改
 * </li>
 *
 * <li><p> Otherwise the channel's key is already in the selected-key set, so its ready-operation set is modified to identify any new operations for which the channel is reported to be ready.
 * Any readiness information previously recorded in the ready set is preserved; in other words, the ready set returned by the underlying system is bitwise-disjoined into the key's current ready set. </p>
 * <p>否则channel对应的key已在selected-key集合中,为准确识别这些channel上报的已准备好的任何新操作,该channel的ready-operation集合将被修改.在ready集合中的之前记录的任何准备信息被保留;换而言之,底层操作系统返回的ready集合被分离到当前ready集合中</p>
 * </li</ol>
 *
 * <p>If all of the keys in the key set at the start of this step have empty interest sets then neither the selected-key set nor any of the keys' ready-operation sets will be updated.
 * <p>若key集合在该步骤开始时拥有空兴趣集,selected-key和key对应的ready-operation集合都不会被更新</p>
 *
 * <li><p> If any keys were added to the cancelled-key set while step (2) was in progress then they are processed as in step (1). </p></li>
 * <p>在(2)步骤处理期间有任何key被添加到cancelled-key集合中,会按照步骤(1)处理</p>
 * </ol>
 *
 * <p> Whether or not a selection operation blocks to wait for one or more channels to become ready, and if so for how long, is the only essential difference between the three selection methods. </p>
 * <p>选择操作是否阻塞等待一个或更多channel准备好,如果这样,阻塞多久是这三个操作方法的本质区别.</p>
 *
 * <h2>Concurrency</h2>
 * <p>并发</p>
 *
 * <p> Selectors are themselves safe for use by multiple concurrent threads; their key sets, however, are not.
 * <p>selector自身是线程安全的,但是其键集合不是</p>
 *
 * <p> The selection operations synchronize on the selector itself, on the key set, and on the selected-key set, in that order.
 * They also synchronize on the cancelled-key set during steps (1) and (3) above.
 * <p>选择操作按序同步自身、键集、selected-key集合,在上述(1)(3)步骤中,通常对cancelled-key集合同步</p>
 *
 * <p> Changes made to the interest sets of a selector's keys while a selection operation is in progress have no effect upon that operation; they will be seen by the next selection operation.
 * <p>当一个选择操作执行中在该操作上无副作用时,改变selector的key的兴趣集</p>
 *
 * <p> Keys may be cancelled and channels may be closed at any time.  Hence the presence of a key in one or more of a selector's key sets does not imply that the key is valid or that its channel is open.
 * <p>key和channel可在任何时候被取消.因此在selector的键的集合种key并不意味着key是有效或者对应的channel是打开的.</p>
 *
 * <p>Application code should be careful to synchronize and check these conditions as necessary if there is any possibility that another thread will cancel a key or close a channel.
 * <p>应用代码要小心同步,检查必要条件,可能有其它线程移除key或关闭channel</p>
 *
 * <p> A thread blocked in one of the {@link #select()} or {@link #select(long)} methods may be interrupted by some other thread in one of three ways:
 * <p>一个阻塞在select()或select(long)方法的线程将被其它线程以如下三种方式打断.</p>
 *
 * <ul><li><p> By invoking the selector's {@link #wakeup wakeup} method,</p></li>
 * <p>通过调用selector的wakeup()方法</p>
 *
 * <li><p> By invoking the selector's {@link #close close} method, or</p></li>
 * <p>通过调用selector的close()方法</p>
 *
 * <li><p> By invoking the blocked thread's {@link java.lang.Thread#interrupt() interrupt} method, in which case its interrupt status will be set and the selector's {@link #wakeup wakeup} method will be invoked. </p></li>
 * <p>通过调用阻塞线程的interrupt()方法,这种情况下,selector的中断状态将被设置,该selector的wakeup()方法将被调用.</p>
 * </ul>ss
 *
 * <p> The {@link #close close} method synchronizes on the selector and all
 * three key sets in the same order as in a selection operation.
 * <p>close()方法在selector和所有三个键集同步,顺序与选择操作相同</p>
 *
 * <a name="ksc"></a><p> A selector's key and selected-key sets are not, in general, safe for use by multiple concurrent threads.
 * If such a thread might modify one of these sets directly then access should be controlled by synchronizing on the set itself.
 * The iterators returned by these sets' {@link java.util.Set#iterator() iterator} methods are <i>fail-fast:</i> If the set is modified after the iterator is created, in any way except by invoking the iterator's own {@link java.util.Iterator#remove() remove} method, then a {@link java.util.ConcurrentModificationException} will be thrown. </p>
 * <p>selector得键集和selected-key集合通常多线程不安全,若这个线程可能直接修改这些集合,集合得获取必须自己通过同步控制</p>
 *
 * @author Mark Reinhold
 * @author JSR-51 Expert Group
 * @see SelectableChannel
 * @see SelectionKey
 * @since 1.4
 */

public abstract class Selector implements Closeable {

    /**
     * Initializes a new instance of this class.
     */
    protected Selector() {
    }

    /**
     * Opens a selector.
     *
     * <p> The new selector is created by invoking the {@link
     * java.nio.channels.spi.SelectorProvider#openSelector openSelector} method
     * of the system-wide default {@link
     * java.nio.channels.spi.SelectorProvider} object.  </p>
     *
     * @return A new selector
     * @throws IOException If an I/O error occurs
     */
    public static Selector open() throws IOException {
        return SelectorProvider.provider().openSelector();
    }

    /**
     * Tells whether or not this selector is open.
     *
     * @return <tt>true</tt> if, and only if, this selector is open
     */
    public abstract boolean isOpen();

    /**
     * Returns the provider that created this channel.
     *
     * @return The provider that created this channel
     */
    public abstract SelectorProvider provider();

    /**
     * Returns this selector's key set.
     *
     * <p> The key set is not directly modifiable.  A key is removed only after
     * it has been cancelled and its channel has been deregistered.  Any
     * attempt to modify the key set will cause an {@link
     * UnsupportedOperationException} to be thrown.
     *
     * <p> The key set is <a href="#ksc">not thread-safe</a>. </p>
     *
     * @return This selector's key set
     * @throws ClosedSelectorException If this selector is closed
     */
    public abstract Set<SelectionKey> keys();

    /**
     * Returns this selector's selected-key set.
     *
     * <p> Keys may be removed from, but not directly added to, the
     * selected-key set.  Any attempt to add an object to the key set will
     * cause an {@link UnsupportedOperationException} to be thrown.
     *
     * <p> The selected-key set is <a href="#ksc">not thread-safe</a>. </p>
     *
     * @return This selector's selected-key set
     * @throws ClosedSelectorException If this selector is closed
     */
    public abstract Set<SelectionKey> selectedKeys();

    /**
     * Selects a set of keys whose corresponding channels are ready for I/O
     * operations.
     *
     * <p> This method performs a non-blocking <a href="#selop">selection
     * operation</a>.  If no channels have become selectable since the previous
     * selection operation then this method immediately returns zero.
     *
     * <p> Invoking this method clears the effect of any previous invocations
     * of the {@link #wakeup wakeup} method.  </p>
     *
     * @return The number of keys, possibly zero, whose ready-operation sets
     * were updated by the selection operation
     * @throws IOException             If an I/O error occurs
     * @throws ClosedSelectorException If this selector is closed
     */
    public abstract int selectNow() throws IOException;

    /**
     * Selects a set of keys whose corresponding channels are ready for I/O
     * operations.
     *
     * <p> This method performs a blocking <a href="#selop">selection
     * operation</a>.  It returns only after at least one channel is selected,
     * this selector's {@link #wakeup wakeup} method is invoked, the current
     * thread is interrupted, or the given timeout period expires, whichever
     * comes first.
     *
     * <p> This method does not offer real-time guarantees: It schedules the
     * timeout as if by invoking the {@link Object#wait(long)} method. </p>
     *
     * @param timeout If positive, block for up to <tt>timeout</tt>
     *                milliseconds, more or less, while waiting for a
     *                channel to become ready; if zero, block indefinitely;
     *                must not be negative
     * @return The number of keys, possibly zero,
     * whose ready-operation sets were updated
     * @throws IOException              If an I/O error occurs
     * @throws ClosedSelectorException  If this selector is closed
     * @throws IllegalArgumentException If the value of the timeout argument is negative
     */
    public abstract int select(long timeout)
            throws IOException;

    /**
     * Selects a set of keys whose corresponding channels are ready for I/O
     * operations.
     *
     * <p> This method performs a blocking <a href="#selop">selection
     * operation</a>.  It returns only after at least one channel is selected,
     * this selector's {@link #wakeup wakeup} method is invoked, or the current
     * thread is interrupted, whichever comes first.  </p>
     *
     * @return The number of keys, possibly zero,
     * whose ready-operation sets were updated
     * @throws IOException             If an I/O error occurs
     * @throws ClosedSelectorException If this selector is closed
     */
    public abstract int select() throws IOException;

    /**
     * Causes the first selection operation that has not yet returned to return
     * immediately.
     *
     * <p> If another thread is currently blocked in an invocation of the
     * {@link #select()} or {@link #select(long)} methods then that invocation
     * will return immediately.  If no selection operation is currently in
     * progress then the next invocation of one of these methods will return
     * immediately unless the {@link #selectNow()} method is invoked in the
     * meantime.  In any case the value returned by that invocation may be
     * non-zero.  Subsequent invocations of the {@link #select()} or {@link
     * #select(long)} methods will block as usual unless this method is invoked
     * again in the meantime.
     *
     * <p> Invoking this method more than once between two successive selection
     * operations has the same effect as invoking it just once.  </p>
     *
     * @return This selector
     */
    public abstract Selector wakeup();

    /**
     * Closes this selector.
     *
     * <p> If a thread is currently blocked in one of this selector's selection
     * methods then it is interrupted as if by invoking the selector's {@link
     * #wakeup wakeup} method.
     *
     * <p> Any uncancelled keys still associated with this selector are
     * invalidated, their channels are deregistered, and any other resources
     * associated with this selector are released.
     *
     * <p> If this selector is already closed then invoking this method has no
     * effect.
     *
     * <p> After a selector is closed, any further attempt to use it, except by
     * invoking this method or the {@link #wakeup wakeup} method, will cause a
     * {@link ClosedSelectorException} to be thrown. </p>
     *
     * @throws IOException If an I/O error occurs
     */
    @Override
    public abstract void close() throws IOException;

}
