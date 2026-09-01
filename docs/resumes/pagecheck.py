import asyncio, os
from playwright.async_api import async_playwright

async def run():
    async with async_playwright() as pw:
        br = await pw.chromium.launch()
        page = await br.new_page()
        for f in ['A-agent.html','B-backend.html']:
            abspath = os.path.abspath(f).replace(os.sep, '/')
            await page.goto('file:///' + abspath)
            await page.wait_for_timeout(1000)
            await page.emulate_media(media='print')
            h = await page.evaluate('document.querySelector("main.sheet").scrollHeight')
            # print 模式下 sheet padding 5mm+6mm 已含；A4 高 297mm = 1122.5px @96dpi
            print(f, 'print-mode sheet-height:', h, 'px =', round(h/1122.5, 3), 'A4 pages')
        await br.close()

asyncio.run(run())
