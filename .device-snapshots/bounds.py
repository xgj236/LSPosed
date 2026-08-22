import io,re,sys
p=sys.argv[1]
s=io.open(p,encoding='utf-8').read()
for m in re.finditer(r'<node[^>]*>', s):
    t=m.group(0)
    def g(k):
        mm=re.search(k+r'="([^"]*)"',t)
        return mm.group(1) if mm else ''
    cls=g('class'); txt=g('text'); rid=g('resource-id'); b=g('bounds')
    desc=g('content-desc'); ck=g('checked'); clk=g('clickable')
    short=cls.split('.')[-1]
    if txt or desc or (rid and 'obfuscated' not in rid) or short in ('Switch','ImageButton','CheckBox'):
        print(f'{b:26} {short:15} clk={clk:5} chk={ck:5} text={txt!r} desc={desc!r}')
