package io.linkmc.core;

import com.google.gson.JsonParser;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.*;
import software.amazon.awssdk.regions.Region;

/** SlimeVault 0.2: SigV4, path-style, writer lease, conditional PUT and exact revision GET. */
public final class SlimeVaultStore implements WorldBlobs.Store {
    private final URI endpoint;
    private final String bucket,prefix;
    private final Aws4SignerParams signing;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public SlimeVaultStore(String endpoint,String region,String bucket,String prefix,String accessKey,String secretKey){
        this.endpoint=URI.create(endpoint);
        if(!Set.of("http","https").contains(this.endpoint.getScheme())||this.endpoint.getHost()==null
            ||this.endpoint.getUserInfo()!=null||this.endpoint.getQuery()!=null||this.endpoint.getFragment()!=null
            ||!(this.endpoint.getPath().isEmpty()||this.endpoint.getPath().equals("/")))throw new IllegalArgumentException("SlimeVault endpoint must be an HTTP(S) origin");
        if(!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")||!prefix.matches("[A-Za-z0-9_/-]*")||prefix.contains(".."))throw new IllegalArgumentException("Invalid SlimeVault bucket or prefix");
        if(accessKey.isBlank()||secretKey.isBlank()||region.isBlank())throw new IllegalArgumentException("Configure SlimeVault credentials and region");
        this.bucket=bucket;this.prefix=prefix.isEmpty()?"":prefix.replaceAll("/+$","")+"/";
        signing=Aws4SignerParams.builder().awsCredentials(AwsBasicCredentials.create(accessKey,secretKey)).signingName("s3").signingRegion(Region.of(region)).doubleUrlEncode(false).build();
    }
    private record Reply(byte[] body,Map<String,List<String>> headers){}
    private Reply request(String method,String bucket,String key,String revision,Map<String,String> headers,byte[] body,int maxBytes)throws Exception{
        var builder=SdkHttpFullRequest.builder().uri(endpoint).method(SdkHttpMethod.fromValue(method))
            .encodedPath("/"+bucket+"/"+key).contentStreamProvider(()->new ByteArrayInputStream(body));
        headers.forEach(builder::putHeader);
        builder.putHeader("x-amz-content-sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body)));
        if(revision!=null)builder.putRawQueryParameter("versionId",revision);
        var signed=Aws4Signer.create().sign(builder.build(),signing);
        var http=HttpRequest.newBuilder(signed.getUri()).timeout(Duration.ofSeconds(60));
        signed.headers().forEach((name,values)->{if(!name.equalsIgnoreCase("host")&&!name.equalsIgnoreCase("content-length"))values.forEach(value->http.header(name,value));});
        http.method(method,body.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
        var response=client.send(http.build(),HttpResponse.BodyHandlers.ofInputStream());
        try(var input=response.body()){
            if(response.statusCode()<200||response.statusCode()>=300)throw new IOException("SlimeVault "+method+" failed: HTTP "+response.statusCode());
            byte[] bytes=input.readNBytes(maxBytes+1);
            if(bytes.length>maxBytes)throw new IOException("SlimeVault response exceeds size limit");
            return new Reply(bytes,response.headers().map());
        }
    }
    private Reply control(String key,String operation,String json)throws Exception{
        return request("PUT",bucket,key,null,Map.of("x-slimevault-operation",operation,"Content-Type","application/json"),json.getBytes(StandardCharsets.UTF_8),16384);
    }
    @Override public WorldBlobs.Revision put(byte[] world)throws Exception{
        if(world.length==0||world.length>512*1024*1024)throw new IllegalArgumentException("Invalid Slime world size");
        // Each upload has a new identity: failed SQL commits can leave harmless unreferenced objects.
        String key=prefix+UUID.randomUUID()+".slime";
        var acquired=control(key,"lease-acquire","{\"ttl_ms\":300000}");
        String token=JsonParser.parseString(new String(acquired.body(),StandardCharsets.UTF_8)).getAsJsonObject()
            .getAsJsonObject("State").getAsJsonObject("lease").get("token").getAsString();
        try{
            var saved=request("PUT",bucket,key,null,Map.of("x-slimevault-lease-token",token,"x-slimevault-if-revision","none","Content-Type","application/octet-stream"),world,16384);
            String version=saved.headers().getOrDefault("x-amz-version-id",List.of("")).getFirst();
            if(!version.matches("[0-9a-fA-F]{64}"))throw new IOException("SlimeVault did not confirm a revision");
            return new WorldBlobs.Revision(bucket,key,version);
        }finally{
            // Bounded lease expires even when cleanup fails; never turn confirmed PUT into a retry.
            try{control(key,"lease-release","{\"token\":"+new com.google.gson.Gson().toJson(token)+"}");}catch(Exception ignored){}
        }
    }
    @Override public byte[] get(WorldBlobs.Revision revision)throws Exception{
        if(!revision.bucket().equals(bucket)||!revision.key().startsWith(prefix)||!revision.key().matches("[A-Za-z0-9_/-]+[.]slime")
            ||!revision.version().matches("[0-9a-fA-F]{64}"))throw new IOException("SlimeVault reference does not match configured storage");
        return request("GET",bucket,revision.key(),revision.version(),Map.of(),new byte[0],512*1024*1024).body();
    }
}
