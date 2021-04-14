/*
 * Copyright 2019-2021 Mamoe Technologies and contributors.
 *
 *  此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 *  Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 *  https://github.com/mamoe/mirai/blob/master/LICENSE
 */

package net.mamoe.mirai.internal.network.net.impl.netty

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import kotlinx.coroutines.CompletableDeferred
import net.mamoe.mirai.internal.network.net.NetworkHandlerContext
import net.mamoe.mirai.internal.network.net.NetworkHandlerFactory
import java.net.SocketAddress

internal object NettyNetworkHandlerFactory : NetworkHandlerFactory<NettyNetworkHandler> {
    override fun create(context: NetworkHandlerContext, address: SocketAddress): NettyNetworkHandler {
        return NettyNetworkHandler(context, address)
    }

}

internal class RetrieveContextInboundHandler : ChannelInboundHandlerAdapter() {
    val instance: CompletableDeferred<ChannelHandlerContext> = CompletableDeferred()

    override fun channelActive(ctx: ChannelHandlerContext) {
        if (!instance.isCompleted) instance.complete(ctx)
    }
}

internal object PacketDecodeInboundHandler : ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext?, msg: Any?) {
        super.channelRead(ctx, msg)
    }
}