package io.github.tbeimu.gongxiaozhu.schedule

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** The real CAS mobile frame calls its parent's form. Only that verified form reaches the bridge. */
internal object ScheduleWebDocument {
    const val POLICY = "default-src 'none'; script-src https://cas.bjut.edu.cn 'unsafe-inline' 'unsafe-eval'; style-src https://cas.bjut.edu.cn 'unsafe-inline'; img-src https://cas.bjut.edu.cn data:; font-src https://cas.bjut.edu.cn data:; frame-src https://cas.bjut.edu.cn; connect-src 'none'; form-action 'none'; worker-src 'none'; object-src 'none'; base-uri 'none'"

    fun render(html: String, base: HttpUrl, token: String): String {
        val doc = prepare(html, base)
        val form = doc.selectFirst("form#loginForm") ?: throw ScheduleException(ScheduleProblem.LOGIN)
        form.attr("action", "#").attr("autocomplete", "off")
        form.select("input[name=username],input[name=password],input[name=captcha]").forEach { it.attr("value", "").attr("autocomplete", "off") }
        // The changing execution token is held by the native session, not sent through the bridge.
        form.select("input[name=execution]").forEach { it.attr("value", "") }
        doc.head().prependElement("script").html("""
            (function(){
              let sent=false;
              function submit(form) {
                if(sent || form.id!=='loginForm') return;
                const values={};
                ['username','password','captcha','type','submit'].forEach(function(name){
                  const input=form.querySelector('[name="'+name+'"]');
                  if(input) values[name]=input.value;
                });
                if(!values.username || !values.password) return;
                sent=true;
                GxzSchedule.postMessage(JSON.stringify({token:${Json.encodeToString(token)},action:'submit',values:values}));
              }
              document.addEventListener('submit',function(event){event.preventDefault();submit(event.target);},true);
              HTMLFormElement.prototype.submit=function(){submit(this);};
              document.addEventListener('click',function(event){if(event.target.closest('a[href]'))event.preventDefault();},true);
              window.open=function(){return null;};
            })();
        """.trimIndent())
        return doc.outerHtml()
    }

    fun sanitizeFrame(html: String, base: HttpUrl): String = prepare(html, base).outerHtml()

    private fun prepare(html: String, base: HttpUrl): Document {
        val doc = Jsoup.parse(html, base.toString())
        doc.select("base,object,embed,meta[http-equiv],#pac4jUrls").remove()
        doc.select("script[src],link[href],img[src],iframe[src]").forEach { node ->
            val attr = if (node.tagName() == "link") "href" else "src"
            val target = base.resolve(node.attr(attr))
            if (target == null || !allowedCasResource(target)) node.remove()
            else node.attr(attr, target.toString())
        }
        doc.select("input[type=password]").attr("autocomplete", "off")
        doc.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content", POLICY)
        doc.head().prependElement("meta").attr("name", "viewport").attr("content", "width=device-width,initial-scale=1")
        // CAS reads a login-method preference during Vue initialization. Supply page-lifetime
        // memory objects while WebView DOM/database persistence stays disabled.
        doc.head().prependElement("script").html("""
            (function(){
              function memoryStorage(){
                const values=Object.create(null);
                Object.defineProperties(values,{
                  getItem:{value:function(k){return Object.prototype.hasOwnProperty.call(this,k)?String(this[k]):null;}},
                  setItem:{value:function(k,v){this[k]=String(v);}},
                  removeItem:{value:function(k){delete this[k];}},
                  clear:{value:function(){Object.keys(this).forEach(k=>delete this[k]);}},
                  key:{value:function(n){return Object.keys(this)[n]||null;}},
                  length:{get:function(){return Object.keys(this).length;}}
                });return values;
              }
              Object.defineProperty(window,'localStorage',{value:memoryStorage()});
              Object.defineProperty(window,'sessionStorage',{value:memoryStorage()});
            })();
        """.trimIndent())
        return doc
    }
}
