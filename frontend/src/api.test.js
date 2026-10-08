import test, {afterEach} from 'node:test';
import assert from 'node:assert/strict';
import {portalRole, portalUrl, api, requestJson, isCancelled} from './api.js';

function page(path) { globalThis.location=new URL(path,'http://localhost:5173'); }
test('role follows each portal URL and survives opening payments or home',()=>{
  page('/manager/login');
  assert.equal(portalRole(),'MANAGER');
  const payments=portalUrl('/payments');
  page(payments);
  assert.equal(portalRole(),'MANAGER');
  assert.equal(portalUrl('/'), '/?portal=MANAGER');
  assert.equal(portalUrl('/api/maintenance/1/photos/p.png'), '/api/maintenance/1/photos/p.png?portal=MANAGER');
  assert.equal(portalUrl('https://example.com/photo.png'), 'https://example.com/photo.png');
  page('/login');assert.equal(portalRole(),'RESIDENT');
  page('/resident/login');assert.equal(portalRole(),'RESIDENT');page('/resident');assert.equal(portalRole(),'RESIDENT');
  page('/provider/login');assert.equal(portalRole(),'PROVIDER');
});
test('API and CSRF requests select the same portal', async()=>{
  page('/manager/login');const calls=[];
  globalThis.fetch=async(url,options)=>{
    calls.push({url,options});
    return url.endsWith('/csrf')?Response.json({headerName:'X-CSRF-TOKEN',token:'test-token'}):new Response(null,{status:204});
  };
  await api('/auth/logout',{method:'POST'});
  assert.equal(calls.length,2);
  for(const call of calls) assert.equal(call.options.headers['X-Community-Portal'],'MANAGER');
  assert.equal(calls[1].options.headers['X-CSRF-TOKEN'],'test-token');
});

const originalFetch = globalThis.fetch;
afterEach(() => {globalThis.fetch = originalFetch;});
test('read recovers from one failed connection', async () => {
  let calls=0;
  globalThis.fetch=async()=>{if(++calls===1)throw new TypeError('Failed to fetch');return Response.json([{id:1}]);};
  assert.deepEqual(await requestJson('/api/payments'),[{id:1}]);assert.equal(calls,2);
});
test('read recovers from a proxy restart', async () => {
  let calls=0;
  globalThis.fetch=async()=>++calls===1?new Response('Restarting',{status:502}):Response.json([]);
  assert.deepEqual(await requestJson('/api/maintenance'),[]);assert.equal(calls,2);
});
test('writes are never retried after an uncertain outcome', async () => {
  let calls=0;globalThis.fetch=async()=>{calls++;throw new TypeError('Failed to fetch');};
  await assert.rejects(requestJson('/api/payments/1/pay',{method:'POST'}),/Cannot connect/);assert.equal(calls,1);
});
test('HTML response is an explicit error, never an empty successful object', async () => {
  globalThis.fetch=async()=>new Response('<html>Proxy page</html>');
  await assert.rejects(requestJson('/api/payments'),/invalid response/);
});
test('empty 200 responses from void endpoints succeed with null', async () => {
  globalThis.fetch=async()=>new Response('',{status:200});
  assert.equal(await requestJson('/api/manager/accounts/4/recovery',{method:'DELETE'}),null);
});
test('access denials are preserved without retry', async () => {
  let calls=0;globalThis.fetch=async()=>{calls++;return Response.json({message:'Please sign in.'},{status:401});};
  await assert.rejects(requestJson('/api/payments'),e=>e.status===401&&e.message==='Please sign in.');assert.equal(calls,1);
});
test('persistent read failures stop after two attempts', async () => {
  let calls=0;globalThis.fetch=async()=>{calls++;throw new TypeError('Failed to fetch');};
  await assert.rejects(requestJson('/api/maintenance'),/Cannot connect/);assert.equal(calls,2);
});
test('a cancelled read stops at once and is not retried, so a newer search wins', async () => {
  let calls=0;
  // Like a real fetch, the pending request rejects with an AbortError when its signal fires.
  globalThis.fetch=(url,{signal})=>{calls++;return new Promise((_,reject)=>signal.addEventListener('abort',()=>reject(new DOMException('Aborted','AbortError'))));};
  const older=new AbortController();
  const pending=requestJson('/api/search?q=old',{signal:older.signal});
  older.abort();
  await assert.rejects(pending,error=>isCancelled(error));
  assert.equal(calls,1);
  await assert.rejects(requestJson('/api/search?q=old',{signal:older.signal}),error=>isCancelled(error));
  assert.equal(calls,1);
});
