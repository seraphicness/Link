// Loopback-only protocol load fixture. Run from the repository root; whitelist LinkLoad0..N first.
const {fork}=require('child_process'),fs=require('fs');
const target=Number(process.argv[2]||51),interval=Number(process.argv[3]||4000),hold=Number(process.argv[4]||60000);
if(!Number.isInteger(target)||target<1||target>80||interval<500||hold<1000)throw Error('Invalid load bounds');
fs.mkdirSync('build/load-clients',{recursive:true});
const clients=[],started=Date.now();let stopping=false;
function report(){const result={elapsedMs:Date.now()-started,target,launched:clients.length,joined:clients.filter(c=>c.joined).length,live:clients.filter(c=>c.joined&&!c.closed).length,failed:clients.filter(c=>c.failed).length,clients:clients.map(({name,joined,closed,failed})=>({name,joined,closed,failed}))};fs.writeFileSync('build/load-clients/status.json',JSON.stringify(result,null,2));}
function stop(){if(stopping)return;stopping=true;for(const c of clients)if(c.child.connected)c.child.send('stop');setTimeout(()=>{for(const c of clients)if(!c.closed)c.child.kill();report();clearInterval(reporter);if(clients.length!==target||clients.some(c=>!c.joined||c.failed))process.exitCode=1;},2000);}
const reporter=setInterval(report,5000);
(async()=>{for(let i=0;i<target&&!stopping;i++){
    const name='LinkLoad'+i,entry={name,joined:false,closed:false,failed:false};
    const child=fork('link-integration/wire-client.cjs',[],{silent:true,env:{...process.env,LINK_WIRE_NAME:name,LINK_WIRE_DURATION_MS:'600000'}});entry.child=child;clients.push(entry);
    const log=fs.createWriteStream('build/load-clients/'+name+'.log');
    child.stdout.on('data',data=>{log.write(data);if(data.toString().includes('PLAY_PHASE'))entry.joined=true;report();});
    child.stderr.on('data',data=>{log.write(data);entry.failed=true;});
    child.on('exit',code=>{entry.closed=true;if(!stopping&&code!==0)entry.failed=true;log.end();report();});
    console.log('LAUNCHED',name);await new Promise(r=>setTimeout(r,interval));
}await new Promise(r=>setTimeout(r,hold));stop();})();
process.on('SIGINT',stop);
