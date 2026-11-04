package io.github.tbeimu.gongxiaozhu.billing

/** Invented fixtures; never capture a real signed-in dashboard (it embeds private credentials). */
internal fun loginHtml(captcha: Boolean = false, action: String = "/Self/login/verify;jsessionid=DEMO", scheme: String = "false", tip: String = "") = """
    <html><form action="$action" method="post">
    <input name="account"><input name="password"><input type="hidden" name="checkcode" value="demo-check">
    <div id="randomDiv" class="form-group ${if (captcha) "" else "hide"}"><input name="code"></div></form>
    <script>if ($scheme && passwordVal != null) { sm3(passwordVal); } (function(tip){})('$tip');</script></html>
""".trimIndent()

internal val dashboardHtml = """
    <html><dl><dt>1234 <small class="unit">MB</small></dt><dd>已用流量</dd></dl>
    <dl><dt>5678 <small class="unit">MB</small></dt><dd>可用流量</dd></dl>
    <dl><dt>未设置</dt><dd>消费保护</dd></dl>
    <dl><dt>12.3456 <small class="unit">元</small></dt><dd>账户余额</dd></dl>
    <table id="OnlineLisBig"></table><script>var demo={"userName":"demo-account"};</script></html>
""".trimIndent()

internal val devicesJson = """[
    {"sessionId":"demo-session-1", "ip":"192.0.2.10", "ipv6":"2001:db8::1", "mac":"00-11-22-33-44-55", "loginTime":"2026-01-01 10:00:00", "useTime":126},
    {"sessionId":"demo-session-1", "ip":"192.0.2.10", "ipv6":"2001:db8::2", "mac":"00-11-22-33-44-55", "useTime":126},
    {"sessionId":"demo-session-2", "ip":"192.0.2.20", "mac":"00-11-22-33-44-66", "useTime":60}
]""".trimIndent()

internal val demoDevice = OnlineDevice("demo-session-1", "192.0.2.10", "2001:db8::1", "00-11-22-33-44-55", "", 2)
internal val demoSnapshot = BillingSnapshot(mapOf("已用流量" to BillingMetric("1234", "MB")), listOf(demoDevice))
