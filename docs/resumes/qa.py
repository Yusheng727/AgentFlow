import asyncio, os
from playwright.async_api import async_playwright

FILES = ['A-agent.html','B-backend.html']

async def run():
    async with async_playwright() as pw:
        br = await pw.chromium.launch()
        page = await br.new_page()
        for f in FILES:
            abspath = os.path.abspath(f).replace(os.sep, '/')
            await page.goto('file:///' + abspath)
            await page.wait_for_timeout(1200)
            out = 'qa-' + f[0] + '.png'
            await page.screenshot(path=out, full_page=True)
            pages = await page.evaluate("document.querySelectorAll('main.resume-page').length")
            text = await page.evaluate("document.querySelectorAll('main.resume-page')[0] ? document.querySelectorAll('main.resume-page')[0].innerText.length : 0")
            print(f, 'resume-page:', pages, 'text-chars:', text)
        await br.close()

asyncio.run(run())
