package com.example.patreonprobe;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;

public final class ProbeActivity extends Activity {
  private static final String POST="https://www.patreon.com/TheBooksofORAR/posts/lich-king-as-4-5-169054023";
  private static final String REDIRECT="webnovelpatreonprobe://oauth/callback";
  private final ExecutorService io=Executors.newSingleThreadExecutor();
  private EditText clientId,secret,tokenBox;
  private TextView output;
  private String state,accessToken;
  private final StringBuilder report=new StringBuilder();
  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    LinearLayout box=new LinearLayout(this); box.setOrientation(1); box.setPadding(24,40,24,20);
    ScrollView scroll=new ScrollView(this); scroll.addView(box); setContentView(scroll);
    TextView title=new TextView(this); title.setText("Patreon acquisition probe\nTemporary app. No library access.\nCredentials stay in memory; reports omit them."); box.addView(title);
    button(box,"Open target chapter in browser",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(POST))));
    button(box,"Check native browser-session access",()->io.execute(this::nativeCheck));
    clientId=new EditText(this); clientId.setHint("OAuth client ID"); clientId.setSingleLine(); box.addView(clientId);
    secret=new EditText(this); secret.setHint("OAuth client secret (temporary test only)"); secret.setSingleLine(); secret.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); box.addView(secret);
    button(box,"Authorize member API access",this::authorize);
    tokenBox=new EditText(this); tokenBox.setHint("or paste access token (creator token from portal)"); tokenBox.setSingleLine(); tokenBox.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); box.addView(tokenBox);
    button(box,"Test authorized post API",()->io.execute(this::apiCheck));
    output=new TextView(this); output.setTextIsSelectable(true); box.addView(output);
    note("Ready. Redirect: "+REDIRECT);
  }
  private void button(LinearLayout box,String label,Runnable action) { Button b=new Button(this);b.setText(label);b.setOnClickListener(v->action.run());box.addView(b); }
  private void note(String line) { synchronized(report) {report.append(line).append('\n'); try(FileOutputStream out=openFileOutput("report.txt",MODE_PRIVATE)){out.write(report.toString().getBytes(StandardCharsets.UTF_8));}catch(IOException ignored){} } runOnUiThread(()->{synchronized(report){output.setText(report.toString());}}); }
  private void authorize() {
    if(clientId.getText().toString().trim().isEmpty()||secret.getText().toString().isEmpty()){note("Enter client ID and secret on this device first.");return;}
    state=UUID.randomUUID().toString();note("Starting authorize flow. Note: Patreon disallows custom-scheme redirect URIs, so "+REDIRECT+" is expected to be rejected; paste a creator token instead.");
    Uri u=Uri.parse("https://www.patreon.com/oauth2/authorize").buildUpon().appendQueryParameter("response_type","code").appendQueryParameter("client_id",clientId.getText().toString().trim()).appendQueryParameter("redirect_uri",REDIRECT).appendQueryParameter("scope","identity identity.memberships campaigns.posts").appendQueryParameter("state",state).build();
    startActivity(new Intent(Intent.ACTION_VIEW,u));
  }
  @Override public void onNewIntent(Intent intent) {super.onNewIntent(intent); Uri u=intent.getData();if(u==null||!"webnovelpatreonprobe".equals(u.getScheme())||!"oauth".equals(u.getHost())||!"/callback".equals(u.getPath()))return;
    if(state==null||!state.equals(u.getQueryParameter("state"))){note("Rejected callback: state mismatch or process restarted.");return;}
    state=null;String code=u.getQueryParameter("code");if(code==null){note("Authorization denied or no code returned.");return;}
    String id=clientId.getText().toString().trim(), s=secret.getText().toString();
    io.execute(()->{try{Result r=request("https://www.patreon.com/api/oauth2/token","code="+enc(code)+"&grant_type=authorization_code&client_id="+enc(id)+"&client_secret="+enc(s)+"&redirect_uri="+enc(REDIRECT),null,false);note("Token exchange HTTP "+r.status);JSONObject j=new JSONObject(r.body);accessToken=j.optString("access_token",null);note("Access token received: "+(accessToken!=null));if(accessToken!=null)apiCheck();}catch(Exception e){note("Token exchange failed: "+e.getClass().getSimpleName());}});
  }
  private void nativeCheck() {
    try {String cookies=CookieManager.getInstance().getCookie(POST);note("App WebView cookie present before native request: "+(cookies!=null&&!cookies.trim().isEmpty()));
      Result r=request(POST,null,null,true);String b=r.body.toLowerCase();note("Native post HTTP "+r.status+"; bytes="+r.body.getBytes(StandardCharsets.UTF_8).length+"; type="+r.contentType+(r.location==null?"":"; redirect="+r.location));
      note("Native HTML indicators: full-body class="+b.contains("patreon-post-content")+", teaser="+b.contains("teaser-post-content")+", paywall="+b.contains("upgrade to unlock")+", challenge="+(b.contains("cf-chl-")||b.contains("just a moment"))+", bootstrap json="+b.contains("window.patreon")+", target title="+b.contains("territory and limits"));
      note("HTML indicators are diagnostic only: Patreon renders client-side, so a missing marker is not proof of blocked content.");
      summarize("V2 API without OAuth",request("https://www.patreon.com/api/oauth2/v2/posts/169054023?fields%5Bpost%5D=title%2Ccontent%2Curl",null,null,false));
      summarize("Internal api/posts without session",request("https://www.patreon.com/api/posts/169054023",null,null,false));
    }catch(Exception e){note("Native check failed: "+e.getClass().getSimpleName());}
  }
  private void apiCheck() {String pasted=tokenBox.getText().toString().trim();String token=pasted.isEmpty()?accessToken:pasted;if(token==null||token.isEmpty()){note("No API token. Authorize or paste one first.");return;}note(pasted.isEmpty()?"Using token from OAuth exchange.":"Using pasted token (in memory only).");try {summarizeIdentity(request("https://www.patreon.com/api/oauth2/v2/identity?include=memberships&fields%5Buser%5D=full_name&fields%5Bmember%5D=patron_status",null,token,false));summarize("Member target post",request("https://www.patreon.com/api/oauth2/v2/posts/169054023?fields%5Bpost%5D=title%2Ccontent%2Curl",null,token,false));}catch(Exception e){note("API check failed: "+e.getClass().getSimpleName());}}
  private void summarizeIdentity(Result r){note("Identity HTTP "+r.status+"; type="+r.contentType);try{JSONObject j=new JSONObject(r.body);JSONObject data=j.optJSONObject("data");JSONObject attrs=data==null?null:data.optJSONObject("attributes");String name=attrs==null?null:attrs.optString("full_name",null);JSONArray inc=j.optJSONArray("included");note("Identity: data="+(data!=null)+", name chars="+(name==null?0:name.length())+", memberships included="+(inc==null?0:inc.length())+", active_patron marker="+r.body.contains("active_patron"));}catch(JSONException e){note("Identity: non-JSON response.");}}
  private void summarize(String name,Result r){note(name+" HTTP "+r.status+"; type="+r.contentType+(r.location==null?"":"; redirect="+r.location));try{JSONObject j=new JSONObject(r.body);JSONObject data=j.optJSONObject("data");JSONObject attrs=data==null?null:data.optJSONObject("attributes");String content=attrs==null?null:attrs.optString("content",null);String title=attrs==null?null:attrs.optString("title",null);note(name+": data="+(data!=null)+", title chars="+(title==null?0:title.length())+", content chars="+(content==null?0:content.length())+", errors="+(j.optJSONArray("errors")!=null));JSONArray errs=j.optJSONArray("errors");if(errs!=null)for(int i=0;i<Math.min(3,errs.length());i++){JSONObject e=errs.optJSONObject(i);if(e!=null)note("API error code="+e.optString("code")+"; name="+e.optString("code_name"));}}catch(JSONException e){note(name+": non-JSON response.");}}
  private record Result(int status,String body,String contentType,String location) {}
  private Result request(String url,String form,String bearer,boolean webCookies)throws Exception {
    HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(20000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","application/json,text/html;q=0.9");
    if(bearer!=null)c.setRequestProperty("Authorization","Bearer "+bearer);
    if(webCookies){String value=CookieManager.getInstance().getCookie(url);if(value!=null)c.setRequestProperty("Cookie",value);}
    if(form!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");try(OutputStream o=c.getOutputStream()){o.write(form.getBytes(StandardCharsets.UTF_8));}}
    int status=c.getResponseCode();InputStream input=status>=400?c.getErrorStream():c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream();if(input!=null)try(input){byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1){if(out.size()+n>2_000_000)throw new IOException("Response limit");out.write(b,0,n);}}
    String contentType=c.getHeaderField("Content-Type"),location=c.getHeaderField("Location");
    c.disconnect();return new Result(status,out.toString(StandardCharsets.UTF_8.name()),contentType,location==null||location.length()<=140?location:location.substring(0,140)+"...");
  }
  private static String enc(String v)throws Exception{return URLEncoder.encode(v,StandardCharsets.UTF_8.name());}
  @Override public void onDestroy(){io.shutdownNow();accessToken=null;super.onDestroy();}
}
