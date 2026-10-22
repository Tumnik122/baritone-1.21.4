import sys, asyncio
sys.stdout.reconfigure(encoding='utf-8')
sys.path.append(r'C:\Users\Administrator\.gemini\antigravity\scratch\gemini-mcp')
import server

async def select_argon_high():
    async with server.bridge.lock:
        page = await server.bridge._ensure_connected()
        await page.keyboard.press("Escape")
        await asyncio.sleep(0.3)
        
        # 1. Open mode selector
        await page.evaluate("""() => {
            const btn = document.querySelector("button[aria-label*='selektor trybu']");
            if (btn) btn.click();
        }""")
        await asyncio.sleep(0.6)
        
        # 2. Click 4 Argon
        await page.evaluate("""() => {
            const items = Array.from(document.querySelectorAll('[role=\"menuitem\"], .mat-mdc-menu-item, button'));
            for (const it of items) {
                if (it.innerText && it.innerText.includes('Argon')) {
                    it.click();
                    return;
                }
            }
        }""")
        await asyncio.sleep(0.8)
        
        # 3. Open mode selector again
        await page.evaluate("""() => {
            const btn = document.querySelector("button[aria-label*='selektor trybu']");
            if (btn) btn.click();
        }""")
        await asyncio.sleep(0.6)
        
        # 4. Click Poziom myślenia
        await page.evaluate("""() => {
            const items = Array.from(document.querySelectorAll('[role=\"menuitem\"], .mat-mdc-menu-item, button'));
            for (const it of items) {
                if (it.innerText && it.innerText.includes('Poziom myślenia')) {
                    it.click();
                    return;
                }
            }
        }""")
        await asyncio.sleep(0.6)
        
        # 5. Click "Rozszerzony" or "Maksymalna dokładność"
        res = await page.evaluate("""() => {
            const items = Array.from(document.querySelectorAll('[role=\"menuitem\"], [role=\"menuitemradio\"], .mat-mdc-menu-item, button, span'));
            for (const it of items) {
                const txt = it.innerText || '';
                if (txt.includes('Rozszerzony') || txt.includes('Maksymalna dokładność') || txt.includes('Wysoki')) {
                    it.click();
                    return 'Selected: ' + txt.replace(/\\n+/g, ' ');
                }
            }
            return 'Not found';
        }""")
        print("Selection result:", res)
        await asyncio.sleep(0.8)
        await page.keyboard.press("Escape")
        
        diag = await page.evaluate(server.JS_DIAGNOSTICS)
        print("Diagnostics after selection:", diag)

asyncio.run(select_argon_high())
