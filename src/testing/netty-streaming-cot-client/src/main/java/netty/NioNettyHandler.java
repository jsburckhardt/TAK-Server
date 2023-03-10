package netty;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import netty.NettyClient.ConnectionLostCallback;

public class NioNettyHandler extends SimpleChannelInboundHandler<byte[]> {
	String SA = "<event version=\"2.0\" uid=\"GLIDER-909\" type=\"a-f-G-U-C-I\" how=\"h-g-i-g-o\" time=\"2022-03-08T21:39:32Z\" start=\"2022-03-08T21:24:53Z\" stale=\"2022-03-08T21:25:38Z\"><point lat=\"41.644598\" lon=\"-70.610919\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\" /><detail><contact callsign=\"glider909\" endpoint=\"*:-1:stcp\"/><__group name=\"Magenta\" role=\"Team Member\"/><status battery=\"100\"/><track speed=\"0.0\" course=\"0.0\"/><uid Droid=\"glider909\"/></detail></event>";
	ScheduledFuture<?> SAFuture = null;
	ConnectionLostCallback connectionLostCallback = null;
	ChannelHandlerContext nettyCtx = null;

	protected NioNettyHandler(ConnectionLostCallback connectionLostCallback) {
		this.connectionLostCallback = connectionLostCallback;
	}

    public ChannelHandlerContext getContext() {
        return nettyCtx;
    }
	
	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		ctx.pipeline().get(SslHandler.class).handshakeFuture()
				.addListener(new GenericFutureListener<Future<Channel>>() {
					@Override
					public void operationComplete(Future<Channel> future) throws Exception {
						if (future.isSuccess()) {
							nettyCtx = ctx;
							ctx.writeAndFlush(SA.getBytes());
						} else {
							ctx.close();
						}
					}
				});
		
		ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
		
		SAFuture = executorService.scheduleWithFixedDelay(() -> {
			ctx.writeAndFlush(SA.getBytes());
		}, 30, 30, TimeUnit.SECONDS);
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, byte[] msg) throws Exception {
		System.out.println(new String(msg));
	}

	@Override
	public void channelUnregistered(ChannelHandlerContext ctx) {
		try {
			super.channelUnregistered(ctx);
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		if (SAFuture != null) SAFuture.cancel(true);
		
		connectionLostCallback.connectionLost();
	}
	
	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		channelUnregistered(ctx);
		ctx.close();
	}
}
