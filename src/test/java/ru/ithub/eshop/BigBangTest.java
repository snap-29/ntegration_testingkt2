package ru.ithub.eshop;
import org.junit.jupiter.api.Test;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class BigBangTest {
 @org.junit.jupiter.api.BeforeAll static void startApplication() throws Exception { TestStand.start(); }
 @org.junit.jupiter.api.AfterAll static void stopApplication() { TestStand.stop(); }
 static final String BASE=System.getProperty("shop.url","http://localhost:5180");
 static class Browser {
  final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
  HttpResponse<String> get(String path)throws Exception{return client.send(HttpRequest.newBuilder(URI.create(BASE+path)).timeout(Duration.ofSeconds(30)).GET().build(),HttpResponse.BodyHandlers.ofString());}
  HttpResponse<String> post(String path,Map<String,String> values)throws Exception{
   String body=values.entrySet().stream().map(e->enc(e.getKey())+"="+enc(e.getValue())).reduce((a,b)->a+"&"+b).orElse("");
   return client.send(HttpRequest.newBuilder(URI.create(BASE+path)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
  }
  Document add()throws Exception{var page=get("/");assertEquals(200,page.statusCode());var form=Jsoup.parse(page.body()).select("form").stream().filter(x->x.selectFirst("input[name=id]")!=null).findFirst().orElseThrow();Map<String,String> data=new HashMap<>();form.select("input[name]").forEach(e->data.put(e.attr("name"),e.attr("value")));var result=post(form.attr("action"),data);assertEquals(200,result.statusCode());return Jsoup.parse(result.body());}
 }
 static String enc(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
 @Test void catalogIsLoadedThroughAllLayers()throws Exception{var r=new Browser().get("/");assertEquals(200,r.statusCode());assertFalse(Jsoup.parse(r.body()).select("input[name=id]").isEmpty());}
 @Test void emptyFilterResult()throws Exception{var r=new Browser().get("/?CatalogModel.BrandFilterApplied=999999");assertEquals(200,r.statusCode());assertTrue(r.body().contains("THERE ARE NO RESULTS"));}
 @Test void catalogToBasket()throws Exception{var b=new Browser();var page=b.add();assertEquals("1",page.selectFirst("input[name='Items[0].Quantity']").val());assertFalse(b.get("/Basket").body().contains("Basket is empty."));}
 @Test void quantityAndRemoval()throws Exception{var b=new Browser();var page=b.add();String id=page.selectFirst("input[name='Items[0].Id']").val();for(String qty:List.of("3","0")){var data=Map.of("Items[0].Id",id,"Items[0].Quantity",qty,"__RequestVerificationToken",page.selectFirst("input[name=__RequestVerificationToken]").val());var r=b.post("/Basket/Update",data);assertEquals(200,r.statusCode());page=Jsoup.parse(r.body());if(qty.equals("3"))assertEquals("3",page.selectFirst("input[name='Items[0].Quantity']").val());else assertTrue(r.body().contains("Basket is empty."));}}
 @Test void anonymousCheckoutRequiresLogin()throws Exception{var b=new Browser();b.add();var r=b.get("/Basket/Checkout");assertTrue(r.uri().getPath().contains("Login"));}
 @Test void missingCsrfRejected()throws Exception{assertEquals(400,new Browser().post("/Basket",Map.of("id","1")).statusCode());}
 @Test void concurrentUsersHaveSeparateBaskets()throws Exception{try(var pool=Executors.newFixedThreadPool(4)){List<Callable<Void>> tasks=new ArrayList<>();for(int i=0;i<8;i++)tasks.add(()->{var b=new Browser();assertTrue(b.get("/Basket").body().contains("Basket is empty."));b.add();assertEquals("1",Jsoup.parse(b.get("/Basket").body()).selectFirst("input[name='Items[0].Quantity']").val());return null;});for(var future:pool.invokeAll(tasks))future.get(45,TimeUnit.SECONDS);}}
 @Test void authenticatedCheckoutCreatesOrder()throws Exception{var b=new Browser();var login=Jsoup.parse(b.get("/Identity/Account/Login").body());var token=login.selectFirst("input[name=__RequestVerificationToken]");assertNotNull(token);b.post("/Identity/Account/Login",Map.of("Input.Email","demouser@microsoft.com","Input.Password","Pass@word1","__RequestVerificationToken",token.val()));b.add();var page=Jsoup.parse(b.get("/Basket/Checkout").body());var r=b.post("/Basket/Checkout",Map.of("Items[0].Id",page.selectFirst("input[name='Items[0].Id']").val(),"Items[0].Quantity","1","__RequestVerificationToken",page.selectFirst("input[name=__RequestVerificationToken]").val()));assertTrue(r.uri().getPath().contains("Success"),r.uri().toString());assertTrue(b.get("/Basket").body().contains("Basket is empty."));assertEquals(200,b.get("/order/my-orders").statusCode());}
 @Test void publicApiCatalogIsAvailable()throws Exception{var r=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:5181/api/catalog-items?pageSize=10&pageIndex=0")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,r.statusCode());assertTrue(r.body().contains("catalogItems"));}
 @Test void wholeSystemHealthMustBeHealthy()throws Exception{var r=new Browser().get("/health");assertEquals(200,r.statusCode(),r.body());assertTrue(r.body().contains("\"status\":\"Healthy\""));}
}
