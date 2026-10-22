import sys, asyncio
sys.stdout.reconfigure(encoding='utf-8')
sys.path.append(r'C:\Users\Administrator\.gemini\antigravity\scratch\gemini-mcp')
import server

async def main():
    async with server.bridge.lock:
        page = await server.bridge._ensure_connected()
        await page.keyboard.press("Escape")
        await asyncio.sleep(0.3)
        
        # 1. Open selector
        await page.evaluate("""() => {
            const btn = document.querySelector("button[aria-label*='selektor trybu']");
            if (btn) btn.click();
        }""")
        await asyncio.sleep(0.6)
        
        # 2. Click 4 Argon
        await page.evaluate("""() => {
            const items = Array.from(document.querySelectorAll('[role="menuitem"], .mat-mdc-menu-item, button'));
            for (const it of items) {
                if (it.innerText && it.innerText.includes('Argon')) {
                    it.click();
                    return;
                }
            }
        }""")
        await asyncio.sleep(0.8)
        
        # 3. Open selector again
        await page.evaluate("""() => {
            const btn = document.querySelector("button[aria-label*='selektor trybu']");
            if (btn) btn.click();
        }""")
        await asyncio.sleep(0.6)
        
        # 4. Hover / Click "Poziom myślenia"
        await page.evaluate("""() => {
            const items = Array.from(document.querySelectorAll('[role="menuitem"], .mat-mdc-menu-item, button'));
            for (const it of items) {
                if (it.innerText && it.innerText.includes('Poziom myślenia')) {
                    it.dispatchEvent(new MouseEvent('mouseenter', { bubbles: true }));
                    it.click();
                    return;
                }
            }
        }""")
        await asyncio.sleep(0.6)
        
        # 5. Click the Rozszerzony / Maksymalna dokładność item
        res = await page.evaluate("""() => {
            const all = Array.from(document.querySelectorAll('*'));
            for (const el of all) {
                if (el.children.length === 0 && (el.innerText === 'Rozszerzony' || el.innerText === 'Maksymalna dokładność')) {
                    (el.closest('button, [role="menuitem"], .mat-mdc-menu-item') || el).click();
                    return 'CLICKED: ' + el.innerText;
                }
            }
            return 'NOT FOUND';
        }""")
        print("Click result:", res)
        await asyncio.sleep(0.8)
        await page.keyboard.press("Escape")
        
        diag = await page.evaluate(server.JS_DIAGNOSTICS)
        print("Final diagnostics:", diag)

asyncio.run(main())
