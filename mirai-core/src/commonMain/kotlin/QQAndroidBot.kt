/*
 * Copyright 2019-2021 Mamoe Technologies and contributors.
 *
 *  此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 *  Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 *  https://github.com/mamoe/mirai/blob/master/LICENSE
 */
@file:Suppress("EXPERIMENTAL_API_USAGE", "INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package net.mamoe.mirai.internal

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.runBlocking
import net.mamoe.mirai.Bot
import net.mamoe.mirai.Mirai
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.event.events.BotOfflineEvent
import net.mamoe.mirai.event.events.BotOnlineEvent
import net.mamoe.mirai.event.events.BotReloginEvent
import net.mamoe.mirai.internal.contact.checkIsGroupImpl
import net.mamoe.mirai.internal.network.component.ComponentStorage
import net.mamoe.mirai.internal.network.component.ConcurrentComponentStorage
import net.mamoe.mirai.internal.network.components.*
import net.mamoe.mirai.internal.network.context.SsoProcessorContext
import net.mamoe.mirai.internal.network.context.SsoProcessorContextImpl
import net.mamoe.mirai.internal.network.handler.NetworkHandler
import net.mamoe.mirai.internal.network.handler.NetworkHandler.State
import net.mamoe.mirai.internal.network.handler.NetworkHandlerContextImpl
import net.mamoe.mirai.internal.network.handler.NetworkHandlerSupport
import net.mamoe.mirai.internal.network.handler.NetworkHandlerSupport.BaseStateImpl
import net.mamoe.mirai.internal.network.handler.selector.FactoryKeepAliveNetworkHandlerSelector
import net.mamoe.mirai.internal.network.handler.selector.SelectorNetworkHandler
import net.mamoe.mirai.internal.network.handler.state.StateChangedObserver
import net.mamoe.mirai.internal.network.handler.state.StateObserver
import net.mamoe.mirai.internal.network.handler.state.StateObserver.Companion.LOGGING
import net.mamoe.mirai.internal.network.handler.state.safe
import net.mamoe.mirai.internal.network.impl.netty.NettyNetworkHandlerFactory
import net.mamoe.mirai.internal.utils.subLogger
import net.mamoe.mirai.utils.BotConfiguration
import net.mamoe.mirai.utils.MiraiLogger
import kotlin.contracts.contract

internal fun Bot.asQQAndroidBot(): QQAndroidBot {
    contract {
        returns() implies (this@asQQAndroidBot is QQAndroidBot)
    }

    return this as QQAndroidBot
}

// for tests
internal class BotDebugConfiguration(
    var stateObserver: StateObserver? = LOGGING,
)

@Suppress("INVISIBLE_MEMBER", "BooleanLiteralArgument", "OverridingDeprecatedMember")
internal open class QQAndroidBot constructor(
    internal val account: BotAccount,
    configuration: BotConfiguration,
    private val debugConfiguration: BotDebugConfiguration = BotDebugConfiguration(),
) : AbstractBot(configuration, account.id) {
    override val bot: QQAndroidBot get() = this
    val client get() = components[SsoProcessor].client


    ///////////////////////////////////////////////////////////////////////////
    // network
    ///////////////////////////////////////////////////////////////////////////

    private val ComponentStorage.eventDispatcher get() = this[EventDispatcher]

    // also called by tests.
    fun ComponentStorage.stateObserverChain(): StateObserver {
        val components = this
        return StateObserver.chainOfNotNull(
            components[BotInitProcessor].asObserver(),
            object : StateChangedObserver(State.OK) {
                private val shouldBroadcastRelogin = atomic(false)

                override fun stateChanged0(
                    networkHandler: NetworkHandlerSupport,
                    previous: BaseStateImpl,
                    new: BaseStateImpl
                ) {
                    components.eventDispatcher.broadcastAsync(BotOnlineEvent(bot)).successThen {
                        if (!shouldBroadcastRelogin.compareAndSet(false, true)) {
                            components.eventDispatcher.broadcastAsync(BotReloginEvent(bot, new.getCause()))
                        }
                    }
                }
            },
            StateChangedObserver(State.OK, State.CONNECTING) { new ->
                components.eventDispatcher.broadcastAsync(BotOfflineEvent.Dropped(bot, new.getCause()))
            },
            StateChangedObserver(State.OK, State.CLOSED) { new ->
                components.eventDispatcher.broadcastAsync(BotOfflineEvent.Active(bot, new.getCause()))
            },
            StateChangedObserver(to = State.OK) { new ->
                components[BotOfflineEventMonitor].attachJob(bot, new)
            },
            StateChangedObserver(State.OK, State.CLOSED) {
                runBlocking {
                    try {
                        components[SsoProcessor].logout(network)
                    } catch (ignored: Exception) {
                    }
                }
            },
            debugConfiguration.stateObserver
        ).safe(logger.subLogger("StateObserver"))
    }


    private val networkLogger: MiraiLogger by lazy { configuration.networkLoggerSupplier(this) }
    override val components: ComponentStorage by lazy {
        createDefaultComponents()
    }

    fun createDefaultComponents(): ConcurrentComponentStorage {
        return ConcurrentComponentStorage().apply {
            val components = this // avoid mistakes

            // There's no need to interrupt a broadcasting event when network handler closed.
            set(EventDispatcher, EventDispatcherImpl(bot.coroutineContext, logger.subLogger("EventDispatcher")))

            set(SsoProcessorContext, SsoProcessorContextImpl(bot))
            set(SsoProcessor, SsoProcessorImpl(get(SsoProcessorContext)))
            set(HeartbeatProcessor, HeartbeatProcessorImpl())
            set(KeyRefreshProcessor, KeyRefreshProcessorImpl(networkLogger.subLogger("KeyRefreshProcessor")))
            set(ConfigPushProcessor, ConfigPushProcessorImpl(networkLogger.subLogger("ConfigPushProcessor")))
            set(BotOfflineEventMonitor, BotOfflineEventMonitorImpl())

            set(BotInitProcessor, BotInitProcessorImpl(bot, components, networkLogger.subLogger("BotInitProcessor")))
            set(ContactCacheService, ContactCacheServiceImpl(bot))
            set(ContactUpdater, ContactUpdaterImpl(bot, components, networkLogger.subLogger("ContactUpdater")))
            set(
                BdhSessionSyncer,
                BdhSessionSyncerImpl(configuration, components, networkLogger.subLogger("BotSessionSyncer"))
            )
            set(ServerList, ServerListImpl(networkLogger.subLogger("ServerList")))
            set(PacketLoggingStrategy, PacketLoggingStrategyImpl(bot))
            set(
                PacketHandler, PacketHandlerChain(
                    LoggingPacketHandlerAdapter(get(PacketLoggingStrategy), networkLogger),
                    EventBroadcasterPacketHandler(components),
                    CallPacketFactoryPacketHandler(bot)
                )
            )
            set(PacketCodec, PacketCodecImpl())
            set(
                OtherClientUpdater,
                OtherClientUpdaterImpl(bot, components, networkLogger.subLogger("OtherClientUpdater"))
            )
            set(ConfigPushSyncer, ConfigPushSyncerImpl())

            set(StateObserver, stateObserverChain())
        }
    }


    override fun createNetworkHandler(): NetworkHandler {
        val context = NetworkHandlerContextImpl(
            this,
            networkLogger,
            components
        )
        return SelectorNetworkHandler(
            context,
            FactoryKeepAliveNetworkHandlerSelector(
                configuration.reconnectionRetryTimes.coerceIn(1, Int.MAX_VALUE),
                NettyNetworkHandlerFactory,
                context
            )
        ) // We can move the factory to configuration but this is not necessary for now.
    }

    /**
     * 获取 获取群公告 所需的 bkn 参数
     * */ // TODO: 2021/4/26 extract it after #1141 merged
    val bkn: Int
        get() = client.wLoginSigInfo.sKey.data
            .fold(5381) { acc: Int, b: Byte -> acc + acc.shl(5) + b.toInt() }
            .and(Int.MAX_VALUE)

    ///////////////////////////////////////////////////////////////////////////
    // contacts
    ///////////////////////////////////////////////////////////////////////////

    override lateinit var nick: String

    // internally visible only
    fun getGroupByUin(uin: Long): Group {
        return getGroupByUinOrNull(uin)
            ?: throw NoSuchElementException("Group ${Mirai.calculateGroupCodeByGroupUin(uin)} not found")
    }

    fun getGroupByUinOrNull(uin: Long): Group? {
        return groups.firstOrNull { it.checkIsGroupImpl(); it.uin == uin }
    }
}
