import {expect,test} from '@playwright/test';

test('public case study never requests private telemetry',async({page})=>{const calls:string[]=[];page.on('request',r=>{if(r.url().includes('/api/'))calls.push(r.url())});await page.goto('/');await expect(page.getByRole('heading',{name:/Vulnerability data/i})).toBeVisible();await expect(page.getByText(/Historical sanitized data/i)).toBeVisible();expect(calls).toEqual([])});

test('public case study keeps navigation and the complete flow usable on mobile',async({page})=>{
  await page.setViewportSize({width:390,height:844});
  await page.goto('/');
  await expect(page.getByRole('link',{name:'VulnFlow'})).toBeVisible();
  await expect(page.getByRole('link',{name:'Private console'})).toBeVisible();
  await expect(page.getByRole('link',{name:'Architecture',exact:true})).toBeVisible();
  await expect(page.getByRole('link',{name:'Evidence',exact:true})).toBeVisible();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
  const flow=page.getByRole('list',{name:'VulnFlow execution zones'});
  await expect(flow).toBeVisible();
  await expect(flow.locator(':scope > [role="listitem"]')).toHaveCount(3);
  expect(await flow.evaluate(element=>element.scrollWidth<=element.clientWidth)).toBe(true);
  const stageNumber=flow.locator('.flow-zone > header > span').first();
  const decisionNumber=page.locator('.decision-grid article > span').first();
  expect(parseFloat(await stageNumber.evaluate(element=>getComputedStyle(element).fontSize))).toBeGreaterThanOrEqual(14);
  expect(parseFloat(await decisionNumber.evaluate(element=>getComputedStyle(element).fontSize))).toBeGreaterThanOrEqual(14);
});

test('public architecture and evidence use the desktop viewport without stretching or clipping',async({page})=>{
  await page.setViewportSize({width:1920,height:1080});
  await page.goto('/');
  const flow=page.getByRole('list',{name:'VulnFlow execution zones'});
  const zones=flow.locator(':scope > [role="listitem"]');
  await expect(zones).toHaveCount(3);
  const widths=await zones.evaluateAll(elements=>elements.map(element=>element.getBoundingClientRect().width));
  expect(Math.min(...widths)).toBeGreaterThan(300);
  expect(Math.max(...widths)-Math.min(...widths)).toBeLessThan(2);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
  const scan=page.locator('#scan');
  const columns=await scan.evaluate(element=>getComputedStyle(element).gridTemplateColumns.split(' ').map(parseFloat));
  expect(columns).toHaveLength(2);
  expect(Math.min(...columns)).toBeGreaterThan(350);
  const firstZone=zones.first();
  expect(await firstZone.locator('ol').evaluate(element=>getComputedStyle(element,'::before').content)).not.toBe('none');
  expect(await firstZone.locator('li').first().evaluate(element=>getComputedStyle(element,'::after').display)).toBe('none');
});

test('login fields remain inside the panel at wide and zoomed-out desktop dimensions',async({page})=>{
  await page.setViewportSize({width:2504,height:1172});
  await page.goto('/login');
  const panel=page.locator('.login-panel');
  const form=panel.locator('form');
  const input=page.getByLabel('Username');
  const boxes=await page.evaluate(()=>{const panel=document.querySelector('.login-panel')!.getBoundingClientRect();const form=document.querySelector('.login-panel form')!.getBoundingClientRect();const input=document.querySelector<HTMLInputElement>('input[name="username"]')!.getBoundingClientRect();return{panel:{left:panel.left,right:panel.right},form:{left:form.left,right:form.right},input:{left:input.left,right:input.right}}});
  expect(boxes.form.right).toBeLessThanOrEqual(boxes.panel.right+1);
  expect(boxes.input.right).toBeLessThanOrEqual(boxes.form.right+1);
  expect(boxes.input.left).toBeGreaterThanOrEqual(boxes.form.left-1);
});

test('operator can sign in and follow an approved scan',async({page})=>{
  const request={id:'11111111-1111-1111-1111-111111111111',targetId:'22222222-2222-2222-2222-222222222222',targetName:'alpine-demo',requestedBy:'operator',agentId:'agent-1',status:'RUNNING',recoveryAttempts:0,requestedAt:'2026-08-29T10:00:00Z',claimedAt:'2026-08-29T10:00:01Z',startedAt:'2026-08-29T10:00:02Z'};
  await page.route('**/api/ui/v1/**',async route=>{const url=new URL(route.request().url());const path=url.pathname;if(path.endsWith('/auth/csrf'))return route.fulfill({json:{token:'test-csrf'}});if(path.endsWith('/auth/login')||path.endsWith('/auth/me'))return route.fulfill({json:{id:'u1',username:'operator',role:'OPERATOR',passwordChangeRequired:false}});if(path.endsWith('/scan-requests/agents'))return route.fulfill({json:[{id:'agent-1',status:'IDLE',online:true}]});if(path.endsWith('/targets'))return route.fulfill({json:[{id:request.targetId,name:'alpine-demo'}]});if(path.endsWith('/scan-requests')&&route.request().method()==='POST'){expect(route.request().postDataJSON()).toEqual({targetId:request.targetId,agentId:'agent-1'});return route.fulfill({json:request});}if(path.endsWith('/scan-requests'))return route.fulfill({json:{content:[request],number:0,totalPages:1,totalElements:1}});if(path.endsWith(request.id))return route.fulfill({json:request});return route.fulfill({status:404,json:{message:'not mocked'}})});
  await page.goto('/login');await page.getByLabel('Username').fill('operator');await page.getByLabel('Password').fill('TemporaryPassword1A');await page.getByRole('button',{name:'Sign in securely'}).click();await page.goto('/app/scans');await page.getByLabel('Scan image',{exact:true}).selectOption(request.targetId);await expect(page.getByRole('heading',{name:'Scans',exact:true})).toBeVisible();await page.getByLabel('Scan agent',{exact:true}).selectOption('agent-1');await page.getByRole('button',{name:'Launch scan',exact:true}).click();await expect(page.getByRole('heading',{name:'alpine-demo'})).toBeVisible();await expect(page.getByText('Execution timeline')).toBeVisible();await expect(page.getByRole('link',{name:'Users'})).toHaveCount(0);
});

test('scan filters and completed result summary use the private API contract',async({page})=>{
  const request={id:'33333333-3333-3333-3333-333333333333',targetId:'44444444-4444-4444-4444-444444444444',targetName:'alpine-demo',requestedBy:'operator',agentId:'agent-1',status:'COMPLETED',recoveryAttempts:0,scanId:'55555555-5555-5555-5555-555555555555',eventId:'66666666-6666-6666-6666-666666666666',contentHash:'hash',scanner:'TRIVY',requestedAt:'2026-08-29T10:00:00Z',completedAt:'2026-08-29T10:00:42Z'};
  await page.route('**/api/ui/v1/**',async route=>{const url=new URL(route.request().url());const path=url.pathname;if(path.endsWith('/auth/csrf'))return route.fulfill({json:{token:'test-csrf'}});if(path.endsWith('/auth/login')||path.endsWith('/auth/me'))return route.fulfill({json:{id:'u1',username:'operator',role:'OPERATOR',passwordChangeRequired:false}});if(path.endsWith('/targets'))return route.fulfill({json:[{id:request.targetId,name:'alpine-demo'}]});if(path.endsWith('/summary'))return route.fulfill({json:{scanId:request.scanId,status:'COMPLETED',scanner:'TRIVY',scannerVersion:'0.58',contentHash:'hash',receivedAt:'2026-08-29T10:00:00Z',completedAt:'2026-08-29T10:00:42Z',findingCount:190,severitySummary:{CRITICAL:7,HIGH:42,MEDIUM:93,LOW:48}}});if(path.endsWith(request.id))return route.fulfill({json:request});if(path.endsWith('/scan-requests'))return route.fulfill({json:{content:[request],number:0,totalPages:1,totalElements:1}});return route.fulfill({status:404,json:{message:'not mocked'}})});
  await page.goto('/login');await page.getByLabel('Username').fill('operator');await page.getByLabel('Password').fill('TemporaryPassword1A');await page.getByRole('button',{name:'Sign in securely'}).click();await page.goto(`/app/scans?status=COMPLETED&targetId=${request.targetId}`);await expect(page.getByLabel('Filter scans by status')).toHaveValue('COMPLETED');await expect(page.getByRole('link',{name:'alpine-demo'})).toBeVisible();await page.getByRole('link',{name:'alpine-demo'}).click();await expect(page.getByText('190')).toBeVisible();await expect(page.getByText('Processing time')).toBeVisible();
});
