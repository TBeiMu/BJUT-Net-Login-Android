package io.github.tbeimu.gongxiaozhu.billing

import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import java.util.Base64
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import org.jsoup.Jsoup

internal class BillingAsset(val type: String, val bytes: ByteArray)

/** The WebView only renders this in-memory login document; it never owns a network session. */
internal object BillingWebDocument {
    fun create(html: String, base: HttpUrl, assets: Map<String, BillingAsset>, captcha: ByteArray,
        token: String, account: AccountDraft): String {
        val doc = Jsoup.parse(html)
        val form = doc.select("form").firstOrNull { it.selectFirst("input[name=account]") != null }
            ?: throw BillingException(BillingProblem.RESPONSE)
        doc.select("iframe, frame, object, embed, base, meta[http-equiv], form:not(.v-form)")
            .filter { it !== form }.forEach { it.remove() }
        doc.select("script[src]").forEach { script ->
            val url = base.resolve(script.attr("src"))
            val asset = url?.let { assets[it.toString()] }
            if (asset == null) script.remove() else {
                script.removeAttr("src")
                script.html(asset.bytes.toString(Charsets.UTF_8).replace("</script", "<\\/script", ignoreCase = true))
            }
        }
        doc.select("link[href]").forEach { link ->
            val asset = base.resolve(link.attr("href"))?.let { assets[it.toString()] }
            if (link.attr("rel") == "stylesheet" && asset != null) {
                link.tagName("style").clearAttributes().html(asset.bytes.toString(Charsets.UTF_8))
            } else link.remove()
        }
        doc.select("img[src]").forEach { img ->
            val asset = base.resolve(img.attr("src"))?.let { assets[it.toString()] }
            if (asset != null && asset.type.startsWith("image/")) img.attr("src", dataUrl(asset.type.substringBefore(';'), asset.bytes))
            else img.removeAttr("src")
        }
        doc.select("a").forEach { it.removeAttr("href").removeAttr("target") }
        doc.select("[autofocus]").removeAttr("autofocus")
        form.attr("action", "#").attr("autocomplete", "off")
        // The bridge supplies the validated hidden token natively, so it need not enter web messages.
        form.selectFirst("input[name=account]")?.attr("readonly", "readonly")
        doc.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content",
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; connect-src 'none'; form-action 'none'; frame-src 'none'; worker-src 'none'")
        val init = """
            (function() {
              const token=${literal(token)}, username=${literal(account.username)}, password=${literal(account.password)};
              const image=${literal(dataUrl("image/png", captcha))};
              let submitted=false, initializedImage=false;
              const nativeSrc=Object.getOwnPropertyDescriptor(HTMLImageElement.prototype,'src');
              const nativeAttribute=Element.prototype.setAttribute;
              function send(value) { window.GxzBilling.postMessage(JSON.stringify(Object.assign({token:token},value))); }
              function setImage(element,value) {
                if(element.id==='codeimage' && String(value).indexOf('login/randomCode')>=0) {
                  nativeSrc.set.call(element,image);
                  if(initializedImage) send({action:'captcha'}); else initializedImage=true;
                  return true;
                }
                return false;
              }
              Object.defineProperty(HTMLImageElement.prototype,'src', {get:nativeSrc.get,set:function(value) {
                if(!setImage(this,value)) nativeSrc.set.call(this,value);
              }});
              Element.prototype.setAttribute=function(name,value) {
                if(this instanceof HTMLImageElement && String(name).toLowerCase()==='src' && setImage(this,value)) return;
                nativeAttribute.call(this,name,value);
              };
              window.gxzSetCaptcha=function(value) { const img=document.getElementById('codeimage'); if(img) nativeSrc.set.call(img,value); };
              function inputProblem(form) {
                const field=form.querySelector('[name=password]'), code=form.querySelector('[name=code]');
                if(!field || !field.value) return '请填写密码';
                const captcha=document.getElementById('randomDiv');
                const visible=captcha && !captcha.hidden && !captcha.classList.contains('hide') && !captcha.classList.contains('hidden') &&
                  getComputedStyle(captcha).display !== 'none' && getComputedStyle(captcha).visibility !== 'hidden';
                if(visible && (!code || code.value.length !== 4)) return '请填写四位验证码';
                return '';
              }
              function showProblem(form,message) {
                let error=document.getElementById('gxz-login-error');
                if(!error) {
                  error=document.createElement('p'); error.id='gxz-login-error'; error.setAttribute('role','alert');
                  error.style.color='#b3261e'; form.appendChild(error);
                }
                error.textContent=message;
                if(message && !submitted) form.querySelectorAll('button[type=submit],input[type=submit],button:not([type])').forEach(function(button){button.disabled=false;});
              }
              // Validate before the school submit handler can disable its button.
              document.addEventListener('submit',function(e) {
                const problem=inputProblem(e.target);
                if(submitted || problem) {
                  e.preventDefault(); e.stopImmediatePropagation();
                  if(problem && !submitted) showProblem(e.target,problem);
                } else showProblem(e.target,'');
              },true);
              function submit(form) {
                if(submitted) return;
                const problem=inputProblem(form);
                if(problem) { showProblem(form,problem); return; }
                showProblem(form,'');
                const field=form.querySelector('[name=password]'), code=form.querySelector('[name=code]');
                submitted=true;
                send({action:'submit',password:field.value,code:code ? code.value : ''});
              }
              HTMLFormElement.prototype.submit=function() { submit(this); };
              document.addEventListener('submit',function(e) {
                if(e.defaultPrevented) return;
                e.preventDefault(); submit(e.target);
              });
              document.addEventListener('DOMContentLoaded',function() {
                const form=document.querySelector('form'), user=form && form.querySelector('[name=account]'), pass=form && form.querySelector('[name=password]');
                if(user) { user.value=username; user.readOnly=true; }
                if(pass) pass.value=password;
                const img=document.getElementById('codeimage'); if(img) nativeSrc.set.call(img,image);
              });
            })();
        """.trimIndent()
        // Bootstrap runs before the school's validation scripts; never submit on page load.
        doc.head().child(0).after(org.jsoup.nodes.Element("script").html(init))
        return doc.outerHtml()
    }

    fun dataUrl(type: String, bytes: ByteArray) = "data:$type;base64,${Base64.getEncoder().encodeToString(bytes)}"
    private fun literal(value: String) = Json.encodeToString(value).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
}
