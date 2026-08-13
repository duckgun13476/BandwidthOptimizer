const translations = {
  'zh-CN': {
    language: '语言', loading: '正在读取报告...', invalidLink: '请打开一条完整的报告链接。', retry: '重试',
    missing: '报告不存在或已过期。', failed: '读取报告失败。', timedOut: '读取报告超时。', title: '带宽报告', month: '月度流量',
    hourly: '玩家小时流量', current: '当前小时', details: '统计阶段', player: '玩家', hour: '小时',
    totalWire: '实际总流量', outboundWire: '出站实际', inboundWire: '入站实际', rawOutbound: '原始出站',
    boFrame: 'BO 帧', bypass: '直通', status: '状态', complete: '已完成', partial: '进行中', noData: '暂无流量数据',
    sessionOutbound: '本次出站实际', sessionInbound: '本次入站实际', monthTotal: '本月实际总流量',
    monthOutbound: '本月出站实际', monthInbound: '本月入站实际', players: '本月玩家',
    metric: '指标', value: '值', stage: '阶段', scope: '范围',
    sections: { transport: '传输管线', server: '服务器会话', chunk: '区块传输与缓存', diagnostics: '诊断' }
  },
  'en-US': {
    language: 'Language', loading: 'Loading report...', invalidLink: 'Open a complete report link.', retry: 'Retry',
    missing: 'The report does not exist or has expired.', failed: 'Failed to load the report.', timedOut: 'Report request timed out.', title: 'Bandwidth report',
    month: 'Monthly traffic', hourly: 'Hourly player traffic', current: 'Current hour', details: 'Accounting stages',
    player: 'Player', hour: 'Hour', totalWire: 'Measured total', outboundWire: 'Outbound measured',
    inboundWire: 'Inbound measured', rawOutbound: 'Outbound raw', boFrame: 'BO frames', bypass: 'Bypass', status: 'Status',
    complete: 'Complete', partial: 'In progress', noData: 'No traffic data', sessionOutbound: 'Session outbound',
    sessionInbound: 'Session inbound', monthTotal: 'Monthly measured total', monthOutbound: 'Monthly outbound',
    monthInbound: 'Monthly inbound', players: 'Monthly players', metric: 'Metric', value: 'Value', stage: 'Stage', scope: 'Scope',
    sections: { transport: 'Transport pipeline', server: 'Server session', chunk: 'Chunk transport and cache', diagnostics: 'Diagnostics' }
  },
  'pt-BR': {
    language: 'Idioma', loading: 'Carregando relatório...', invalidLink: 'Abra um link completo de relatório.', retry: 'Tentar novamente',
    missing: 'O relatório não existe ou expirou.', failed: 'Falha ao carregar o relatório.', timedOut: 'A leitura do relatório expirou.', title: 'Relatório de largura de banda',
    month: 'Tráfego mensal', hourly: 'Tráfego por jogador e hora', current: 'Hora atual', details: 'Etapas de contabilização',
    player: 'Jogador', hour: 'Hora', totalWire: 'Total medido', outboundWire: 'Saída medida', inboundWire: 'Entrada medida',
    rawOutbound: 'Saída original', boFrame: 'Quadros BO', bypass: 'Direto', status: 'Estado', complete: 'Concluído',
    partial: 'Em andamento', noData: 'Sem dados de tráfego', sessionOutbound: 'Saída da sessão',
    sessionInbound: 'Entrada da sessão', monthTotal: 'Total mensal medido', monthOutbound: 'Saída mensal',
    monthInbound: 'Entrada mensal', players: 'Jogadores no mês', metric: 'Métrica', value: 'Valor', stage: 'Etapa', scope: 'Escopo',
    sections: { transport: 'Pipeline de transporte', server: 'Sessão do servidor', chunk: 'Transporte e cache de chunks', diagnostics: 'Diagnóstico' }
  }
};

const languageNode = document.querySelector('#language');
const supportedLanguage = value => value === 'pt-BR' ? value : value?.startsWith('zh') ? 'zh-CN' : 'en-US';
let language = localStorage.getItem('bostats.language') || supportedLanguage(navigator.language);
if (!translations[language]) language = 'en-US';
let reportBundle = null;
const t = key => translations[language][key] || translations['en-US'][key] || key;
const statusNode = document.querySelector('#status');
const statusTextNode = document.querySelector('#status-text');
const retryNode = document.querySelector('#retry');
let statusKey = 'loading';
languageNode.value = language;
setStatus('loading', false);
const bytes = value => {
  let number = Number(value || 0);
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
  let unit = 0;
  while (number >= 1024 && unit < units.length - 1) { number /= 1024; unit++; }
  return `${number.toFixed(unit === 0 ? 0 : 2)} ${units[unit]}`;
};
const metricValue = metric => metric.unit === 'bytes' ? bytes(metric.value) : `${metric.value} ${metric.unit}`;
const metric = (label, value) => `<div class="metric"><small>${label}</small><strong>${value}</strong></div>`;

async function load() {
  const match = location.pathname.match(/^\/report\/([A-Za-z0-9_-]+)$/);
  if (!match) { setStatus('invalidLink', false); return; }
  setStatus('loading', false);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(`/api/v1/reports/${match[1]}`, { cache: 'no-store', signal: controller.signal });
    if (!response.ok) {
      setStatus(response.status === 404 ? 'missing' : 'failed', true);
      return;
    }
    reportBundle = await response.json();
  } finally {
    clearTimeout(timeout);
  }
  render();
}

function setStatus(key, retry) {
  statusKey = key;
  statusTextNode.textContent = t(key);
  retryNode.textContent = t('retry');
  retryNode.hidden = !retry;
  statusNode.hidden = false;
}

function showLoadError(error) {
  setStatus(error?.name === 'AbortError' ? 'timedOut' : 'failed', true);
}

function render() {
  const bundle = reportBundle;
  if (!bundle) return;
  const summary = bundle.summary;
  const traffic = bundle.playerTraffic;
  const history = bundle.trafficHistory;
  document.documentElement.lang = language;
  document.title = `BO Stats - ${t('title')}`;
  document.querySelector('#language-label').textContent = t('language');
  languageNode.setAttribute('aria-label', t('language'));
  document.querySelector('#page-title').textContent = t('title');
  document.querySelector('#month-title').textContent = t('month');
  document.querySelector('#hourly-title').textContent = t('hourly');
  document.querySelector('#current-title').textContent = t('current');
  document.querySelector('#details-title').textContent = t('details');
  document.querySelectorAll('[data-i18n]').forEach(node => { node.textContent = t(node.dataset.i18n); });
  document.querySelector('#report-id').textContent = bundle.reportId;
  document.querySelector('#generated').textContent = formatDateTime(bundle.generatedAtMillis);

  const server = summary.sections.find(section => section.id === 'server');
  const values = Object.fromEntries((server?.metrics || []).map(item => [item.id, item.value]));
  const monthTotals = history?.totals || traffic?.totals || {};
  const monthPlayers = history?.players || traffic?.players || [];
  document.querySelector('#summary').innerHTML = [
    metric(t('sessionOutbound'), bytes(values.outbound_wire_bytes)),
    metric(t('sessionInbound'), bytes(values.inbound_wire_bytes)),
    metric(t('monthTotal'), bytes((monthTotals.outboundWireBytes || 0) + (monthTotals.inboundWireBytes || 0))),
    metric(t('monthOutbound'), bytes(monthTotals.outboundWireBytes)),
    metric(t('monthInbound'), bytes(monthTotals.inboundWireBytes)),
    metric(t('players'), `${monthPlayers.length}`),
  ].join('');

  const monthRows = [...monthPlayers].sort((a, b) => totalWire(b.traffic) - totalWire(a.traffic));
  document.querySelector('#month-players').innerHTML = monthRows.map(player => `<tr>
    <td>${escapeText(player.playerName)}<span class="uuid">${escapeText(player.playerUuid)}</span></td>
    <td>${bytes(totalWire(player.traffic))}</td><td>${bytes(player.traffic.outboundWireBytes)}</td>
    <td>${bytes(player.traffic.inboundWireBytes)}</td><td>${bytes(player.traffic.outboundRawBytes)}</td></tr>`).join('') || emptyRow(5);
  document.querySelector('#month-period').textContent = history
    ? `${formatDate(history.periodStartMillis)} - ${formatDate(history.periodEndMillis - 1)}`
    : '';

  renderHourly(history, monthRows);

  const currentRows = [...(traffic?.players || [])].sort((a, b) => b.traffic.outboundWireBytes - a.traffic.outboundWireBytes);
  document.querySelector('#current-players').innerHTML = currentRows.map(player => `<tr>
    <td>${escapeText(player.playerName)}<span class="uuid">${escapeText(player.playerUuid)}</span></td>
    <td>${bytes(player.traffic.outboundWireBytes)}</td><td>${bytes(player.traffic.inboundWireBytes)}</td>
    <td>${bytes(player.traffic.outboundRawBytes)}</td><td>${bytes(player.traffic.outboundTransportBytes)}</td>
    <td>${bytes(player.traffic.outboundBypassBytes)}</td></tr>`).join('') || emptyRow(6);

  document.querySelector('#sections').innerHTML = summary.sections.map(section => `<div class="section">
    <h3>${escapeText(t('sections')[section.id] || section.title)}</h3><p>${escapeText(section.description)}</p>
    <div class="table-wrap"><table><thead><tr><th>${t('metric')}</th><th>${t('value')}</th><th>${t('stage')}</th><th>${t('scope')}</th></tr></thead><tbody>
    ${section.metrics.map(item => `<tr><td>${escapeText(item.label)}</td><td>${escapeText(metricValue(item))}</td><td>${escapeText(item.stage)}</td><td>${escapeText(item.scope)}</td></tr>`).join('')}
    </tbody></table></div></div>`).join('');

  statusNode.hidden = true;
  document.querySelector('#overview').hidden = false;
  document.querySelector('#month-section').hidden = false;
  document.querySelector('#hourly-section').hidden = !history;
  document.querySelector('#current-section').hidden = false;
  document.querySelector('#details').hidden = false;
}

function renderHourly(history, monthPlayers) {
  const playerSelect = document.querySelector('#hourly-player');
  const previous = playerSelect.value;
  playerSelect.innerHTML = monthPlayers.map(player =>
    `<option value="${escapeAttribute(player.playerUuid)}">${escapeText(player.playerName)}</option>`).join('');
  if (monthPlayers.some(player => player.playerUuid === previous)) playerSelect.value = previous;
  const uuid = playerSelect.value;
  const rows = (history?.hours || []).map(hour => {
    const player = (hour.players || []).find(entry => entry.playerUuid === uuid);
    const outbound = player?.outboundWireBytes || 0;
    const inbound = player?.inboundWireBytes || 0;
    return `<tr><td>${formatHour(hour.periodStartMillis)}</td><td><meter class="traffic-bar" min="0" max="100" value="${hourShare(history, uuid, outbound + inbound)}"></meter>${bytes(outbound + inbound)}</td>
      <td>${bytes(outbound)}</td><td>${bytes(inbound)}</td><td><span class="state ${hour.complete ? 'complete' : 'partial'}">${hour.complete ? t('complete') : t('partial')}</span></td></tr>`;
  });
  document.querySelector('#hourly-rows').innerHTML = rows.join('') || emptyRow(5);
}

function hourShare(history, uuid, value) {
  const maximum = Math.max(1, ...(history?.hours || []).map(hour => {
    const player = (hour.players || []).find(entry => entry.playerUuid === uuid);
    return (player?.outboundWireBytes || 0) + (player?.inboundWireBytes || 0);
  }));
  return Math.min(100, Math.round(value * 100 / maximum));
}

function totalWire(traffic) { return Number(traffic?.outboundWireBytes || 0) + Number(traffic?.inboundWireBytes || 0); }
function emptyRow(columns) { return `<tr><td colspan="${columns}" class="empty">${t('noData')}</td></tr>`; }
function formatDate(value) { return new Intl.DateTimeFormat(language, { dateStyle: 'medium' }).format(new Date(value)); }
function formatDateTime(value) { return new Intl.DateTimeFormat(language, { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value)); }
function formatHour(value) { return new Intl.DateTimeFormat(language, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value)); }

function escapeText(value) {
  const node = document.createElement('span');
  node.textContent = value == null ? '' : String(value);
  return node.innerHTML;
}

function escapeAttribute(value) {
  return String(value == null ? '' : value)
    .replaceAll('&', '&amp;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;');
}

languageNode.addEventListener('change', () => {
  language = languageNode.value;
  localStorage.setItem('bostats.language', language);
  if (reportBundle) render();
  else setStatus(statusKey, !retryNode.hidden);
});
document.querySelector('#hourly-player').addEventListener('change', () => renderHourly(reportBundle?.trafficHistory, reportBundle?.trafficHistory?.players || []));
retryNode.addEventListener('click', () => load().catch(showLoadError));
load().catch(showLoadError);
