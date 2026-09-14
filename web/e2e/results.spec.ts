import {expect,test} from '@playwright/test';

test('failed execution shows failure and no successful completion',async({page})=>{
  await page.route('**/api/ui/v1/**',route=>route.fulfill({json:route.request().url().endsWith('/auth/me')?{id:'u',username:'admin',role:'ADMIN',passwordChangeRequired:false}:{id:'failed',targetName:'Test image',status:'FAILED',requestedAt:'2026-08-29T10:00:00Z',startedAt:'2026-08-29T10:00:01Z',completedAt:'2026-08-29T10:00:02Z',safeError:'Scan execution failed',recoveryAttempts:0}}));
  await page.goto('/app/scans/failed');
  await expect(page.getByRole('heading',{name:'Scan failed',exact:true})).toBeVisible();
  await expect(page.locator('.timeline').getByText('COMPLETED',{exact:true})).toHaveCount(0);
  await expect(page.getByText('No report was ingested, so there are no findings or processing identifiers.')).toBeVisible();
  await page.screenshot({path:'test-results/failed-desktop.png',fullPage:true});
});

test('findings selector follows AWS cursor and shows the next results',async({page})=>{
  await page.route('**/api/ui/v1/**',route=>{
    const url=new URL(route.request().url());
    if(url.pathname.endsWith('/auth/me'))return route.fulfill({json:{id:'u',username:'admin',role:'ADMIN',passwordChangeRequired:false}});
    if(url.pathname.endsWith('/scan-requests'))return route.fulfill({json:{content:[{id:'scan',targetName:'Demo image',completedAt:'2026-08-29T10:00:00Z'}],number:0,totalPages:1,totalElements:1}});
    return route.fulfill({json:{content:[{vulnerabilityId:url.searchParams.get('cursor')?'CVE-SECOND':'CVE-FIRST',packageName:'test',severity:'HIGH'}],nextCursor:url.searchParams.get('cursor')?null:'next-token',number:0,totalPages:2}});
  });
  await page.goto('/app/findings');
  await page.getByLabel('Completed scan').selectOption('scan');
  await expect(page.getByText('CVE-FIRST')).toBeVisible();
  await page.getByRole('button',{name:'Next findings'}).click();
  await expect(page.getByText('CVE-SECOND')).toBeVisible();
  await page.setViewportSize({width:390,height:844});
  await page.screenshot({path:'test-results/findings-mobile.png',fullPage:true});
});

test('overview labels every severity without hover',async({page})=>{
  await page.route('**/api/ui/v1/**',route=>route.fulfill({json:route.request().url().endsWith('/auth/me')?{id:'u',username:'admin',role:'ADMIN',passwordChangeRequired:false}:{since:'2026-08-29T10:00:00Z',scanLimit:500,scans:4,assets:2,findings:190,severity:{HIGH:19,CRITICAL:6,LOW:97,MEDIUM:60,UNKNOWN:8}}}));
  await page.goto('/app');
  await expect(page.locator('.severity-total strong')).toHaveText('190');
  await expect(page.locator('.severity-overview').getByText('UNKNOWN',{exact:true})).toBeVisible();
  await page.screenshot({path:'test-results/overview-desktop.png',fullPage:true});
});
