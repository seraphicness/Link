// Minimal 26.3 test client. Run from repository root after applyAllPatches.
// Use only a loopback, offline-auth test proxy. This does not test Mojang authentication.
const fs=require('fs'),net=require('net'),zlib=require('zlib'),crypto=require('crypto');
const root='aspaper-server/src/minecraft/java/net/minecraft/network/protocol/';
function table(file,start,end){const source=fs.readFileSync(root+file,'utf8');const body=source.slice(source.indexOf(start),end?source.indexOf(end):undefined);return [...body.matchAll(/(?:addPacket|withBundlePacket)\(\s*\w+\.(\w+)/g)].map(m=>m[1]);}
const inbound={login:table('login/LoginProtocols.java','CLIENTBOUND_TEMPLATE'),config:table('configuration/ConfigurationProtocols.java','CLIENTBOUND_TEMPLATE'),game:table('game/GameProtocols.java','CLIENTBOUND_TEMPLATE')};
const outbound={login:table('login/LoginProtocols.java','SERVERBOUND_TEMPLATE','public static final ProtocolInfo<ServerLoginPacketListener>'),config:table('configuration/ConfigurationProtocols.java','SERVERBOUND_TEMPLATE','public static final ProtocolInfo<ServerConfigurationPacketListener>'),game:table('game/GameProtocols.java','SERVERBOUND_TEMPLATE','public static final SimpleUnboundProtocol<ClientGamePacketListener')};
function vint(n){const bytes=[];do{let v=n&127;n>>>=7;if(n)v|=128;bytes.push(v);}while(n);return Buffer.from(bytes);}
function readVar(b,offset=0){let n=0;for(let i=0;i<5;i++){if(offset+i>=b.length)return null;const c=b[offset+i];n|=(c&127)<<(7*i);if(!(c&128))return [n,offset+i+1];}throw Error('Invalid VarInt');}
function string(s){const b=Buffer.from(s);return Buffer.concat([vint(b.length),b]);}
let state='login',compression=-1,buffer=Buffer.alloc(0),joined=0,position;
const socket=net.connect(25594,'127.0.0.1',()=>{
    const port=Buffer.alloc(2);port.writeUInt16BE(25594);
    raw(Buffer.concat([vint(0),vint(777),string('localhost'),port,vint(2)]));
    const uuid=crypto.createHash('md5').update('OfflinePlayer:LinkWire').digest();uuid[6]=(uuid[6]&15)|48;uuid[8]=(uuid[8]&63)|128;
    send('SERVERBOUND_HELLO',Buffer.concat([string('LinkWire'),uuid]));
});
function raw(payload){if(compression>=0)payload=payload.length>=compression?Buffer.concat([vint(payload.length),zlib.deflateSync(payload)]):Buffer.concat([vint(0),payload]);socket.write(Buffer.concat([vint(payload.length),payload]));}
function send(name,payload=Buffer.alloc(0)){const id=outbound[state].indexOf(name);if(id<0)throw Error('Unknown '+state+' packet '+name);raw(Buffer.concat([vint(id),payload]));}
function information(){send('SERVERBOUND_CLIENT_INFORMATION',Buffer.concat([string('en_us'),Buffer.from([2,0,1,127,1,0,1,0])]));}
function receive(name,data){
    if(name?.endsWith('DISCONNECT')){console.error('DISCONNECTED',state,data.toString('utf8'));socket.end();process.exitCode=1;return;}
    if(name==='CLIENTBOUND_HELLO')throw Error('Proxy requires authentication; use a disposable offline-auth test proxy');
    if(name==='CLIENTBOUND_LOGIN_COMPRESSION'){compression=readVar(data)[0];return;}
    if(name==='CLIENTBOUND_LOGIN_FINISHED'){send('SERVERBOUND_LOGIN_ACKNOWLEDGED');state='config';information();console.log('LOGIN_FINISHED');return;}
    if(name==='CLIENTBOUND_CUSTOM_QUERY'){const id=readVar(data);send('SERVERBOUND_CUSTOM_QUERY_ANSWER',Buffer.concat([data.subarray(0,id[1]),Buffer.from([0])]));return;}
    if(name==='CLIENTBOUND_COOKIE_REQUEST'){send('SERVERBOUND_COOKIE_RESPONSE',Buffer.concat([data,Buffer.from([0])]));return;}
    if(name==='CLIENTBOUND_KEEP_ALIVE'){send('SERVERBOUND_KEEP_ALIVE',data);return;}
    if(name==='CLIENTBOUND_PING'){send('SERVERBOUND_PONG',data);return;}
    if(name==='CLIENTBOUND_SELECT_KNOWN_PACKS'){send('SERVERBOUND_SELECT_KNOWN_PACKS',data);return;}
    if(name==='CLIENTBOUND_CODE_OF_CONDUCT'){send('SERVERBOUND_ACCEPT_CODE_OF_CONDUCT');return;}
    if(name==='CLIENTBOUND_FINISH_CONFIGURATION'){send('SERVERBOUND_FINISH_CONFIGURATION');state='game';console.log('PLAY_PHASE',++joined);return;}
    if(name==='CLIENTBOUND_START_CONFIGURATION'){send('SERVERBOUND_CONFIGURATION_ACKNOWLEDGED');state='config';console.log('RECONFIGURE');return;}
    if(name==='CLIENTBOUND_PLAYER_POSITION'){
        const id=readVar(data),offset=id[1],flags=data.readInt32BE(offset+56),previous=position||{x:0,y:0,z:0,yaw:0,pitch:0};
        position={x:data.readDoubleBE(offset)+(flags&1?previous.x:0),y:data.readDoubleBE(offset+8)+(flags&2?previous.y:0),z:data.readDoubleBE(offset+16)+(flags&4?previous.z:0),yaw:data.readFloatBE(offset+48)+(flags&8?previous.yaw:0),pitch:data.readFloatBE(offset+52)+(flags&16?previous.pitch:0)};
        const coordinates=Buffer.alloc(32);coordinates.writeDoubleBE(position.x,0);coordinates.writeDoubleBE(position.y,8);coordinates.writeDoubleBE(position.z,16);coordinates.writeFloatBE(position.yaw,24);coordinates.writeFloatBE(position.pitch,28);
        send('SERVERBOUND_ACCEPT_TELEPORTATION',Buffer.concat([data.subarray(0,id[1]),coordinates]));send('SERVERBOUND_PLAYER_LOADED');console.log('POSITION',position);return;
    }
    if(name==='CLIENTBOUND_CHUNK_BATCH_FINISHED'){const response=Buffer.alloc(4);response.writeFloatBE(10);send('SERVERBOUND_CHUNK_BATCH_RECEIVED',response);}
}
socket.on('data',chunk=>{try{buffer=Buffer.concat([buffer,chunk]);while(buffer.length){const size=readVar(buffer);if(!size)return;if(size[0]<0||size[0]>16*1024*1024)throw Error('Frame size');if(buffer.length<size[1]+size[0])return;let payload=buffer.subarray(size[1],size[1]+size[0]);buffer=buffer.subarray(size[1]+size[0]);if(compression>=0){const unpacked=readVar(payload);payload=unpacked[0]?zlib.inflateSync(payload.subarray(unpacked[1]),{maxOutputLength:16*1024*1024}):payload.subarray(unpacked[1]);}const packet=readVar(payload);receive(inbound[state][packet[0]],payload.subarray(packet[1]));}}catch(error){console.error(error);socket.destroy();process.exitCode=1;}});
socket.on('error',e=>{console.error(e.message);process.exitCode=1;});
const tick=setInterval(()=>{if(state==='game'&&!socket.destroyed){send('SERVERBOUND_CLIENT_TICK_END');}},50);
const deadline=setTimeout(()=>{console.log('TEST_TIMEOUT');socket.destroy();process.exitCode=1;},300000);
socket.on('close',()=>{clearInterval(tick);clearTimeout(deadline);console.log('CLOSED');});
process.on('SIGINT',()=>socket.end());
