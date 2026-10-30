package io.linkmc.server;

import io.netty.buffer.*;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class LinkPacketGateTestSuite {
    EmbeddedChannel channel;
    @BeforeEach void start(){channel=new EmbeddedChannel(new LinkPacketGate(true));}
    @AfterEach void stop(){LinkPacketGate.abort(new IllegalStateException("test cleanup"));channel.finishAndReleaseAll();}
    @Test void exposesBytesOnlyAfterCommitAndPreservesOrder(){
        LinkPacketGate.begin();
        var first=Unpooled.buffer().writeInt(1);var promise=channel.newPromise();
        channel.writeAndFlush(first,promise);channel.runPendingTasks();
        assertNull(channel.readOutbound());assertFalse(promise.isDone());
        LinkPacketGate.publish();channel.writeAndFlush(Unpooled.buffer().writeInt(2));channel.runPendingTasks();
        ByteBuf one=channel.readOutbound(),two=channel.readOutbound();
        assertEquals(1,one.readInt());assertEquals(2,two.readInt());one.release();two.release();assertTrue(promise.isSuccess());
    }
    @Test void abortReleasesHeldBuffersWithoutSending(){
        LinkPacketGate.begin();var bytes=Unpooled.buffer().writeInt(1);var promise=channel.newPromise();
        channel.writeAndFlush(bytes,promise);channel.runPendingTasks();
        LinkPacketGate.abort(new IllegalStateException("database unavailable"));channel.runPendingTasks();
        assertEquals(0,bytes.refCnt());assertFalse(promise.isSuccess());assertNull(channel.readOutbound());assertFalse(channel.isOpen());
    }
    @Test void adjacentFramesDoNotMix(){
        LinkPacketGate.begin();channel.writeAndFlush(Unpooled.buffer().writeInt(1));channel.runPendingTasks();LinkPacketGate.publish();
        LinkPacketGate.begin();channel.writeAndFlush(Unpooled.buffer().writeInt(2));channel.runPendingTasks();
        ByteBuf one=channel.readOutbound();assertEquals(1,one.readInt());one.release();assertNull(channel.readOutbound());
        LinkPacketGate.publish();channel.runPendingTasks();ByteBuf two=channel.readOutbound();assertEquals(2,two.readInt());two.release();
    }
    @Test void protocolConfigurationCompletesSynchronouslyDuringFrame(){
        channel.pipeline().addFirst("configuration",new io.netty.channel.ChannelOutboundHandlerAdapter(){
            @Override public void write(io.netty.channel.ChannelHandlerContext ctx,Object msg,io.netty.channel.ChannelPromise promise){
                if(msg instanceof Runnable task){task.run();promise.setSuccess();}else ctx.write(msg,promise);
            }
        });
        LinkPacketGate.begin();
        var changed=new java.util.concurrent.atomic.AtomicBoolean();
        var promise=channel.newPromise();
        channel.write((Runnable)()->changed.set(true),promise);
        assertTrue(changed.get());assertTrue(promise.isSuccess());
        channel.writeAndFlush(Unpooled.buffer().writeInt(7));
        assertNull(channel.readOutbound());
        LinkPacketGate.publish();channel.runPendingTasks();
        ByteBuf bytes=channel.readOutbound();assertEquals(7,bytes.readInt());bytes.release();
    }
    @Test void heldBytesAreUnaffectedByNewCompressionHandler(){
        LinkPacketGate.begin();
        channel.writeAndFlush(Unpooled.buffer().writeInt(1));
        channel.pipeline().addLast("new-encoding",new io.netty.handler.codec.MessageToByteEncoder<ByteBuf>(){
            @Override protected void encode(io.netty.channel.ChannelHandlerContext ctx,ByteBuf input,ByteBuf output){
                output.writeInt(99);output.writeBytes(input);
            }
        });
        channel.writeAndFlush(Unpooled.buffer().writeInt(2));
        LinkPacketGate.publish();channel.runPendingTasks();
        ByteBuf before=channel.readOutbound(),after=channel.readOutbound();
        assertEquals(1,before.readInt());assertFalse(before.isReadable());
        assertEquals(99,after.readInt());assertEquals(2,after.readInt());
        before.release();after.release();
    }
}
