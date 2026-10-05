/* UNOONE_DOM_ADAPTER_V2 — unprivileged page claims, never native authority. */
(() => {
  const ids = new WeakMap(), nodes = new Map(); let next = 1;
  const identity = e => { if (!ids.has(e)) { ids.set(e, next++); nodes.set(ids.get(e), e); } return ids.get(e); };
  const clean = (x, max=240) => String(x ?? '').replace(/[<>\r\n\u0000-\u001f]/g, ' ').slice(0,max);
  const sensitive = e => /password|one.time|otp|cc-|credit|captcha|passcode/i.test([e.type,e.name,e.id,e.autocomplete,e.getAttribute('aria-label')].join(' '));
  const describe = e => ({tag:e.tagName.toLowerCase(),type:e.type||'',id:clean(e.id),name:clean(e.name),label:clean(e.getAttribute('aria-label')||Array.from(e.labels||[]).map(l=>l.textContent).join(' ')||e.textContent),href:clean(e.href),formAction:clean(e.form?.action),formMethod:clean(e.form?.method),disabled:!!e.disabled,secret:sensitive(e),handover:e.isContentEditable||e.type==='file',options:e.tagName==='SELECT'?Array.from(e.options).map(o=>[clean(o.value),clean(o.text)]):[],value:sensitive(e)?'[redacted]':clean(e.value,2000),checked:!!e.checked,validation:sensitive(e)?'':clean(e.validationMessage)});
  const fingerprint = e => JSON.stringify([location.href,identity(e),describe(e)]);
  const visible = e => e.isConnected && !e.disabled && e.getClientRects().length>0;
  const snapshot = () => ({url:clean(location.href,2000),title:clean(document.title),text:clean(document.body?.innerText,4000),headings:Array.from(document.querySelectorAll('h1,h2,h3,[role="heading"],[role="alert"]')).filter(visible).slice(0,40).map(e=>clean(e.textContent)),limitations:'Frames, shadow-root and contenteditable controls require native user handover.',elements:Array.from(document.querySelectorAll('input,textarea,select,button,a[href],[role="button"],[contenteditable="true"],[role="textbox"],[tabindex]')).filter(visible).slice(0,150).map(e=>({index:identity(e),fingerprint:fingerprint(e),summary:JSON.stringify(describe(e))}))});
  const target = a => { const e=nodes.get(a.index); if(!e||!visible(e)||fingerprint(e)!==a.fingerprint)throw Error('STALE_TARGET');return e; };
  const date = a => { if(typeof a.date!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(a.date)||Number.isNaN(Date.parse(a.date))||new Date(a.date).toISOString().slice(0,10)!==a.date)throw Error('INVALID_DATE');return a.date; };
  function act(a) {
    if(['scroll','scroll_horizontally'].includes(a.action)) {
      const e=a.index==null?window:target(a), horizontal=a.action==='scroll_horizontally';
      const direction=horizontal?a.right:a.down;
      if(typeof direction!=='boolean')throw Error('INVALID_DIRECTION');
      const pages=a.num_pages??1, pixels=a.pixels??pages*(horizontal?(e.innerWidth||e.clientWidth):(e.innerHeight||e.clientHeight));
      if(!Number.isFinite(pixels)||pixels<=0||pixels>5000||!Number.isFinite(pages)||pages<=0||pages>5)throw Error('INVALID_SCROLL');
      const distance=pixels*(direction?1:-1);e.scrollBy(horizontal?distance:0,horizontal?0:distance);return {dispatched:true};
    }
    const e=target(a);
    if(sensitive(e)||e.type==='file'||e.isContentEditable||e.getAttribute('role')==='textbox')throw Error('USER_HANDOVER_REQUIRED');
    switch(a.action) {
      case 'input_text': case 'pick_date': {
        if(!['INPUT','TEXTAREA'].includes(e.tagName)||['submit','button','checkbox','radio','hidden'].includes(e.type))throw Error('WRONG_TARGET');
        const value=a.action==='pick_date'?date(a):a.text;
        if(typeof value!=='string'||value.length===0)throw Error('MISSING_VALUE');
        if(a.action==='pick_date'&&e.type!=='date')throw Error('WRONG_TARGET');
        const proto=e.tagName==='INPUT'?HTMLInputElement.prototype:HTMLTextAreaElement.prototype;
        Object.getOwnPropertyDescriptor(proto,'value').set.call(e,value);
        e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));break;
      }
      case 'select_dropdown_option': {
        if(e.tagName!=='SELECT')throw Error('WRONG_TARGET');
        const o=Array.from(e.options).find(o=>o.text===a.text);if(!o)throw Error('UNKNOWN_OPTION');e.value=o.value;e.dispatchEvent(new Event('change',{bubbles:true}));break;
      }
      case 'toggle_checkbox': case 'choose_radio': {
        if(e.type!==(a.action==='choose_radio'?'radio':'checkbox'))throw Error('WRONG_TARGET');
        const desired=a.action==='choose_radio'?true:a.checked;
        if(typeof desired!=='boolean')throw Error('MISSING_STATE');if(e.checked!==desired)e.click();break;
      }
      case 'click_element_by_index':e.click();break;
      default:throw Error('UNSUPPORTED_ACTION');
    }
    return {dispatched:true};
  }
  function verify(a) {
    const e=nodes.get(a.index);if(!e||!visible(e)||sensitive(e))return {verified:false};
    let matched=false;
    if(a.action==='input_text')matched=typeof a.text==='string'&&a.text.length>0&&e.value===a.text;
    if(a.action==='pick_date')matched=e.value===date(a);
    if(a.action==='select_dropdown_option')matched=Array.from(e.selectedOptions||[]).some(o=>o.text===a.text);
    if(a.action==='toggle_checkbox')matched=typeof a.checked==='boolean'&&e.checked===a.checked;
    if(a.action==='choose_radio')matched=e.checked===true;
    return {verified:matched};
  }
  Object.defineProperty(window,'UnoOneDomAdapter',{value:{version:2,observe:snapshot,act,verify},configurable:true});
  return true;
})();
