/*
 *
 * Copyright 2017-2018 549477611@qq.com(xiaoyu)
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.raincat.manager.netty.impl;

import com.google.common.base.StandardSystemProperty;
import com.raincat.common.enums.SerializeProtocolEnum;
import com.raincat.common.exception.TransactionRuntimeException;
import com.raincat.manager.config.NettyConfig;
import com.raincat.manager.netty.NettyService;
import com.raincat.manager.netty.handler.NettyServerHandlerInitializer;
import com.raincat.manager.socket.SocketManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * NettyServerServiceImpl.
 * @author xiaoyu
 */
@Component
public class NettyServerServiceImpl implements NettyService, DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyServerServiceImpl.class);

    private static final String OS_NAME = "Linux";

    private static int maxThread = Runtime.getRuntime().availableProcessors() << 1;

    private EventLoopGroup bossGroup;

    private EventLoopGroup workerGroup;

    private DefaultEventExecutorGroup servletExecutor;

    private final NettyConfig nettyConfig;

    private final NettyServerHandlerInitializer nettyServerHandlerInitializer;

    @Autowired(required = false)
    public NettyServerServiceImpl(final NettyConfig nettyConfig,
                                  final NettyServerHandlerInitializer nettyServerHandlerInitializer) {
        this.nettyConfig = nettyConfig;
        this.nettyServerHandlerInitializer = nettyServerHandlerInitializer;
    }

    @Override
    public void start() {
        SocketManager.getInstance().setMaxConnection(nettyConfig.getMaxConnection());
        if (nettyConfig.getMaxThreads() != 0) {
            maxThread = nettyConfig.getMaxThreads();
        }
        servletExecutor = new DefaultEventExecutorGroup(maxThread);
        try {
            final SerializeProtocolEnum serializeProtocolEnum =
                    SerializeProtocolEnum.acquireSerializeProtocol(nettyConfig.getSerialize());
            nettyServerHandlerInitializer.setSerializeProtocolEnum(serializeProtocolEnum);
            nettyServerHandlerInitializer.setServletExecutor(servletExecutor);
            ServerBootstrap b = new ServerBootstrap();
            groups(b, maxThread << 1);
            b.bind(nettyConfig.getPort());
            LOGGER.info("netty service started on port: " + nettyConfig.getPort());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void groups(final ServerBootstrap b, final int workThreads) {
        if (Objects.equals(StandardSystemProperty.OS_NAME.value(), OS_NAME)) {
            bossGroup = new EpollEventLoopGroup(1);
            workerGroup = new EpollEventLoopGroup(workThreads);
            b.group(bossGroup, workerGroup)
                    .channel(EpollServerSocketChannel.class)
                    .option(EpollChannelOption.TCP_CORK, true)
                    .option(EpollChannelOption.SO_KEEPALIVE, true)
                    .option(EpollChannelOption.SO_BACKLOG, 100)
                    .option(EpollChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .childOption(EpollChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(nettyServerHandlerInitializer);
        } else {
            bossGroup = new NioEventLoopGroup();
            workerGroup = new NioEventLoopGroup(workThreads);
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 100)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 100)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(nettyServerHandlerInitializer);
        }
    }

    private void stop() {
        try {
            if (null != bossGroup) {
                bossGroup.shutdownGracefully().await();
            }
            if (null != workerGroup) {
                workerGroup.shutdownGracefully().await();
            }
            if (null != servletExecutor) {
                servletExecutor.shutdownGracefully().await();
            }
        } catch (InterruptedException e) {
            throw new TransactionRuntimeException(" Netty  Container stop interrupted", e);
        }

    }

    @Override
    public void destroy() {
        stop();
    }
}
