const translations = {
  'zh-CN': {
    language: '语言', loading: '正在读取报告...', invalidLink: '请打开一条完整的报告链接。', retry: '重试',
    missing: '报告不存在或已过期。', failed: '读取报告失败。', timedOut: '读取报告超时。', title: '带宽报告',
    subtitle: '服务器带宽使用与优化结果', trend: '小时流量趋势', dataRange: '数据范围', serverTotal: '全服总量',
    tabOverview: '总览', tabPlayers: '玩家', tabDetails: '传输详情', pageNavigation: '报告页面', detailsSubtitle: '各压缩阶段与运行状态',
    month: '月度玩家排行', hourly: '玩家小时明细', current: '当前小时', details: '传输详情', player: '玩家', hour: '小时',
    totalWire: '实际总流量', outboundWire: '出站实际', inboundWire: '入站实际', rawOutbound: '原始出站', boFrame: 'BO 帧',
    bypass: '旁路', status: '状态', complete: '已完成', partial: '进行中', noData: '暂无流量数据', sessionOutbound: '本次出站实际',
    sessionInbound: '本次入站实际', monthTotal: '本月实际总流量', monthOutbound: '本月出站实际', monthInbound: '本月入站实际',
    players: '本月玩家', metric: '指标', value: '值', stage: '阶段', scope: '范围', total: '总流量', outbound: '出站', inbound: '入站',
    records: '{count} 条记录', page: '第 {page} / {pages} 页', rowsPerPage: '每页', previous: '上一页', next: '下一页',
    enabled: '已开启', disabled: '已关闭', chartLabel: '按小时统计的实际线路流量曲线',
    sections: { transport: '传输管线', server: '服务器会话', chunk: '区块传输与缓存', diagnostics: '诊断状态' },
    descriptions: {
      transport: '逻辑包、映射、Zstd、BO 帧与旁路是相互独立的统计阶段。',
      server: '服务器聚合计数，不包含地址或连接标识。',
      chunk: '区块协议流量、缓存复用与当前内存保留量。', diagnostics: '仅显示诊断工具开关，不包含诊断日志内容。'
    },
    stages: { logical_packet: '逻辑包', minecraft_compression_estimate: '原版压缩估算', mapping: '映射', zstd_body: 'Zstd 主体', bo_frame: 'BO 帧', socket_wire: '实际线路', derived: '派生值', chunk_reuse: '区块复用', gate_input: '门控输入', gate_suppressed: '门控削减', chunk_frame: '区块帧', memory: '内存', connection: '连接', diagnostic: '诊断' },
    scopes: { outbound: '出站', inbound: '入站', server_session: '服务器会话', session: '会话', current: '当前' },
    units: { frames: '帧', packets: '包', entries: '项', channels: '连接', players: '玩家' }
  },
  'en-US': {
    language: 'Language', loading: 'Loading report...', invalidLink: 'Open a complete report link.', retry: 'Retry',
    missing: 'The report does not exist or has expired.', failed: 'Failed to load the report.', timedOut: 'Report request timed out.', title: 'Bandwidth report',
    subtitle: 'Server bandwidth use and optimization results', trend: 'Hourly traffic trend', dataRange: 'Data range', serverTotal: 'All players',
    tabOverview: 'Overview', tabPlayers: 'Players', tabDetails: 'Transport details', pageNavigation: 'Report pages', detailsSubtitle: 'Compression stages and runtime state',
    month: 'Monthly player ranking', hourly: 'Player hourly details', current: 'Current hour', details: 'Transport details', player: 'Player', hour: 'Hour',
    totalWire: 'Measured total', outboundWire: 'Outbound measured', inboundWire: 'Inbound measured', rawOutbound: 'Outbound raw', boFrame: 'BO frames',
    bypass: 'Bypass', status: 'Status', complete: 'Complete', partial: 'In progress', noData: 'No traffic data', sessionOutbound: 'Session outbound',
    sessionInbound: 'Session inbound', monthTotal: 'Monthly measured total', monthOutbound: 'Monthly outbound', monthInbound: 'Monthly inbound',
    players: 'Monthly players', metric: 'Metric', value: 'Value', stage: 'Stage', scope: 'Scope', total: 'Total', outbound: 'Outbound', inbound: 'Inbound',
    records: '{count} records', page: 'Page {page} of {pages}', rowsPerPage: 'Rows', previous: 'Previous page', next: 'Next page',
    enabled: 'Enabled', disabled: 'Disabled', chartLabel: 'Measured wire traffic by hour',
    sections: { transport: 'Transport pipeline', server: 'Server session', chunk: 'Chunk transport and cache', diagnostics: 'Diagnostic state' },
    descriptions: {
      transport: 'Logical packets, mapping, Zstd, BO frames, and bypass are separate accounting stages.',
      server: 'Aggregate server counters without addresses or connection identifiers.',
      chunk: 'Chunk protocol traffic, cache reuse, and current retained memory.', diagnostics: 'Diagnostic tool state only; log contents are not embedded.'
    },
    stages: { logical_packet: 'Logical packet', minecraft_compression_estimate: 'Vanilla estimate', mapping: 'Mapping', zstd_body: 'Zstd body', bo_frame: 'BO frame', socket_wire: 'Measured wire', derived: 'Derived', chunk_reuse: 'Chunk reuse', gate_input: 'Gate input', gate_suppressed: 'Gate suppressed', chunk_frame: 'Chunk frame', memory: 'Memory', connection: 'Connection', diagnostic: 'Diagnostic' },
    scopes: { outbound: 'Outbound', inbound: 'Inbound', server_session: 'Server session', session: 'Session', current: 'Current' },
    units: { frames: 'frames', packets: 'packets', entries: 'entries', channels: 'channels', players: 'players' }
  },
  'pt-BR': {
    language: 'Idioma', loading: 'Carregando relatório...', invalidLink: 'Abra um link completo de relatório.', retry: 'Tentar novamente',
    missing: 'O relatório não existe ou expirou.', failed: 'Falha ao carregar o relatório.', timedOut: 'A leitura do relatório expirou.', title: 'Relatório de largura de banda',
    subtitle: 'Uso de largura de banda e resultados da otimização', trend: 'Tendência de tráfego por hora', dataRange: 'Intervalo de dados', serverTotal: 'Todos os jogadores',
    tabOverview: 'Visão geral', tabPlayers: 'Jogadores', tabDetails: 'Detalhes do transporte', pageNavigation: 'Páginas do relatório', detailsSubtitle: 'Etapas de compressão e estado de execução',
    month: 'Ranking mensal de jogadores', hourly: 'Detalhes por jogador e hora', current: 'Hora atual', details: 'Detalhes do transporte', player: 'Jogador', hour: 'Hora',
    totalWire: 'Total medido', outboundWire: 'Saída medida', inboundWire: 'Entrada medida', rawOutbound: 'Saída original', boFrame: 'Quadros BO',
    bypass: 'Desvio', status: 'Estado', complete: 'Concluído', partial: 'Em andamento', noData: 'Sem dados de tráfego', sessionOutbound: 'Saída da sessão',
    sessionInbound: 'Entrada da sessão', monthTotal: 'Total mensal medido', monthOutbound: 'Saída mensal', monthInbound: 'Entrada mensal',
    players: 'Jogadores no mês', metric: 'Métrica', value: 'Valor', stage: 'Etapa', scope: 'Escopo', total: 'Total', outbound: 'Saída', inbound: 'Entrada',
    records: '{count} registros', page: 'Página {page} de {pages}', rowsPerPage: 'Linhas', previous: 'Página anterior', next: 'Próxima página',
    enabled: 'Ativado', disabled: 'Desativado', chartLabel: 'Tráfego medido por hora',
    sections: { transport: 'Pipeline de transporte', server: 'Sessão do servidor', chunk: 'Transporte e cache de chunks', diagnostics: 'Estado do diagnóstico' },
    descriptions: {
      transport: 'Pacotes lógicos, mapeamento, Zstd, quadros BO e desvio são etapas contábeis separadas.',
      server: 'Contadores agregados sem endereços ou identificadores de conexão.',
      chunk: 'Tráfego de chunks, reutilização de cache e memória retida atualmente.', diagnostics: 'Apenas o estado das ferramentas; o conteúdo dos logs não é incluído.'
    },
    stages: { logical_packet: 'Pacote lógico', minecraft_compression_estimate: 'Estimativa vanilla', mapping: 'Mapeamento', zstd_body: 'Corpo Zstd', bo_frame: 'Quadro BO', socket_wire: 'Tráfego medido', derived: 'Derivado', chunk_reuse: 'Reuso de chunks', gate_input: 'Entrada do gate', gate_suppressed: 'Suprimido pelo gate', chunk_frame: 'Quadro de chunk', memory: 'Memória', connection: 'Conexão', diagnostic: 'Diagnóstico' },
    scopes: { outbound: 'Saída', inbound: 'Entrada', server_session: 'Sessão do servidor', session: 'Sessão', current: 'Atual' },
    units: { frames: 'quadros', packets: 'pacotes', entries: 'entradas', channels: 'conexões', players: 'jogadores' }
  }
};

const metricLabels = {
  'zh-CN': { frames: '帧', packets: '逻辑包', baseline_bytes: '逻辑字节', vanilla_estimate_bytes: '原版压缩估算', mapping_bytes: '映射字节', zstd_body_bytes: 'Zstd 主体字节', bo_frame_bytes: 'BO 帧字节', bypass_packets: '旁路包', bypass_bytes: '旁路字节', literal_entries: '原文项', exact_refs: '精确引用', template_refs: '模板引用', active_channels: '活跃连接', bound_players: '已绑定玩家', outbound_raw_bytes: '出站原始字节', outbound_vanilla_estimate_bytes: '出站原版估算', outbound_wire_bytes: '出站实际线路', inbound_wire_bytes: '入站实际线路', outbound_saved_bytes: '出站节省', offline_reuse_saved_bytes: '离线缓存节省', temporary_reuse_saved_bytes: '临时缓存节省', create_gate_observed_bytes: 'Create 门控观察量', create_gate_saved_bytes: 'Create 门控节省', idle_gate_saved_bytes: '挂机门控节省', 'hotspot.outbound.logical_bytes': '热点出站逻辑字节', 'hotspot.outbound.frame_bytes': '热点出站帧字节', 'hotspot.inbound.logical_bytes': '热点入站逻辑字节', 'shadow.total_bytes': '影子快照总量', 'shadow.retained_original_bytes': '影子原始保留量', 'runtime.total_bytes': '运行时引用量', 'reuse.temporary_saved_bytes': '客户端临时复用节省', 'reuse.offline_saved_bytes': '客户端离线复用节省' },
  'pt-BR': { frames: 'Quadros', packets: 'Pacotes lógicos', baseline_bytes: 'Bytes lógicos', vanilla_estimate_bytes: 'Estimativa vanilla', mapping_bytes: 'Bytes de mapeamento', zstd_body_bytes: 'Bytes do corpo Zstd', bo_frame_bytes: 'Bytes dos quadros BO', bypass_packets: 'Pacotes desviados', bypass_bytes: 'Bytes desviados', literal_entries: 'Entradas literais', exact_refs: 'Referências exatas', template_refs: 'Referências de modelo', active_channels: 'Conexões ativas', bound_players: 'Jogadores vinculados', outbound_raw_bytes: 'Saída original', outbound_vanilla_estimate_bytes: 'Estimativa vanilla de saída', outbound_wire_bytes: 'Saída medida', inbound_wire_bytes: 'Entrada medida', outbound_saved_bytes: 'Saída economizada', offline_reuse_saved_bytes: 'Economia do cache offline', temporary_reuse_saved_bytes: 'Economia do cache temporário', create_gate_observed_bytes: 'Observado pelo gate Create', create_gate_saved_bytes: 'Economia do gate Create', idle_gate_saved_bytes: 'Economia do gate AFK', 'hotspot.outbound.logical_bytes': 'Saída lógica hotspot', 'hotspot.outbound.frame_bytes': 'Quadros de saída hotspot', 'hotspot.inbound.logical_bytes': 'Entrada lógica hotspot', 'shadow.total_bytes': 'Total do snapshot shadow', 'shadow.retained_original_bytes': 'Original retido no shadow', 'runtime.total_bytes': 'Referências em execução', 'reuse.temporary_saved_bytes': 'Reuso temporário do cliente', 'reuse.offline_saved_bytes': 'Reuso offline do cliente' }
};

const languageNode = document.querySelector('#language');
const supportedLanguage = value => value === 'pt-BR' ? value : value?.startsWith('zh') ? 'zh-CN' : 'en-US';
let language = localStorage.getItem('bostats.language') || supportedLanguage(navigator.language);
if (!translations[language]) language = 'en-US';
let reportBundle = null;
const visibleSeries = new Set(['total', 'outbound', 'inbound']);
const pages = { month: { page: 1, size: 10 }, hourly: { page: 1, size: 10 }, current: { page: 1, size: 10 } };
let activeView = ['overview', 'players', 'details'].includes(location.hash.slice(1)) ? location.hash.slice(1) : 'overview';
let chartHoverIndex = -1;
const t = key => translations[language][key] || translations['en-US'][key] || key;
const template = (key, values) => Object.entries(values).reduce((text, [name, value]) => text.replace(`{${name}}`, value), t(key));
const statusNode = document.querySelector('#status');
const statusTextNode = document.querySelector('#status-text');
const retryNode = document.querySelector('#retry');
const chartNode = document.querySelector('#traffic-chart');
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
const metric = (label, value) => `<div class="metric"><small>${escapeText(label)}</small><strong>${escapeText(value)}</strong></div>`;

async function load() {
  const match = location.pathname.match(/^\/report\/([A-Za-z0-9_-]+)$/);
  if (!match) { setStatus('invalidLink', false); return; }
  setStatus('loading', false);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(`/api/v1/reports/${match[1]}`, { cache: 'no-store', signal: controller.signal });
    if (!response.ok) { setStatus(response.status === 404 ? 'missing' : 'failed', true); return; }
    reportBundle = await response.json();
  } finally { clearTimeout(timeout); }
  render();
}

function setStatus(key, retry) {
  statusKey = key;
  statusTextNode.textContent = t(key);
  retryNode.textContent = t('retry');
  retryNode.hidden = !retry;
  statusNode.hidden = false;
}

function showLoadError(error) { setStatus(error?.name === 'AbortError' ? 'timedOut' : 'failed', true); }

function render() {
  if (!reportBundle) return;
  const bundle = reportBundle;
  const summary = bundle.summary;
  const traffic = bundle.playerTraffic;
  const history = bundle.trafficHistory;
  document.documentElement.lang = language;
  document.title = `BO Stats - ${t('title')}`;
  document.querySelector('#language-label').textContent = t('language');
  languageNode.setAttribute('aria-label', t('language'));
  document.querySelector('#page-title').textContent = t('title');
  document.querySelector('#page-subtitle').textContent = t('subtitle');
  document.querySelector('#view-tabs').setAttribute('aria-label', t('pageNavigation'));
  document.querySelector('[data-view="overview"]').textContent = t('tabOverview');
  document.querySelector('[data-view="players"]').textContent = t('tabPlayers');
  document.querySelector('[data-view="details"]').textContent = t('tabDetails');
  document.querySelector('#trend-title').textContent = t('trend');
  document.querySelector('#trend-player-label').textContent = t('dataRange');
  document.querySelector('#month-title').textContent = t('month');
  document.querySelector('#hourly-title').textContent = t('hourly');
  document.querySelector('#current-title').textContent = t('current');
  document.querySelector('#details-title').textContent = t('details');
  document.querySelector('#details-subtitle').textContent = t('detailsSubtitle');
  document.querySelectorAll('[data-i18n]').forEach(node => { node.textContent = t(node.dataset.i18n); });
  document.querySelector('#report-id').textContent = bundle.reportId;
  document.querySelector('#generated').textContent = formatDateTime(bundle.generatedAtMillis);

  const server = summary.sections.find(section => section.id === 'server');
  const values = Object.fromEntries((server?.metrics || []).map(item => [item.id, item.value]));
  const monthTotals = history?.totals || traffic?.totals || {};
  const monthPlayers = history?.players || traffic?.players || [];
  document.querySelector('#summary').innerHTML = [
    metric(t('sessionOutbound'), bytes(values.outbound_wire_bytes)), metric(t('sessionInbound'), bytes(values.inbound_wire_bytes)),
    metric(t('monthTotal'), bytes(totalWire(monthTotals))), metric(t('monthOutbound'), bytes(monthTotals.outboundWireBytes)),
    metric(t('monthInbound'), bytes(monthTotals.inboundWireBytes)), metric(t('players'), `${monthPlayers.length}`)
  ].join('');

  populatePlayerSelectors(monthPlayers);
  renderTrend(history);
  renderMonth(monthPlayers, history);
  renderHourly(history);
  renderCurrent(traffic?.players || []);
  renderDetails(summary.sections || []);

  statusNode.hidden = true;
  document.querySelector('#trend-section').hidden = !history;
  document.querySelector('#hourly-section').hidden = !history;
  setActiveView(activeView, false);
}

function populatePlayerSelectors(players) {
  const sorted = [...players].sort((a, b) => a.playerName.localeCompare(b.playerName, language));
  const trend = document.querySelector('#trend-player');
  const trendPrevious = trend.value || '__server';
  trend.innerHTML = `<option value="__server">${escapeText(t('serverTotal'))}</option>${playerOptions(sorted)}`;
  trend.value = sorted.some(player => player.playerUuid === trendPrevious) ? trendPrevious : '__server';
  const hourly = document.querySelector('#hourly-player');
  const hourlyPrevious = hourly.value;
  hourly.innerHTML = playerOptions(sorted);
  if (sorted.some(player => player.playerUuid === hourlyPrevious)) hourly.value = hourlyPrevious;
}

function playerOptions(players) {
  return players.map(player => `<option value="${escapeAttribute(player.playerUuid)}">${escapeText(player.playerName)}</option>`).join('');
}

function renderTrend(history) {
  if (!history) return;
  document.querySelector('#trend-period').textContent = `${formatDate(history.periodStartMillis)} - ${formatDate(history.periodEndMillis - 1)}`;
  const legend = document.querySelector('#trend-legend');
  legend.innerHTML = ['total', 'outbound', 'inbound'].map(series => `<button type="button" class="legend-${series}" data-series="${series}" aria-pressed="${visibleSeries.has(series)}"><span class="legend-dot"></span>${escapeText(t(series))}</button>`).join('');
  legend.querySelectorAll('button').forEach(button => button.addEventListener('click', () => {
    const series = button.dataset.series;
    if (visibleSeries.has(series) && visibleSeries.size === 1) return;
    visibleSeries.has(series) ? visibleSeries.delete(series) : visibleSeries.add(series);
    renderTrend(history);
  }));
  chartNode.setAttribute('aria-label', t('chartLabel'));
  drawChart(history);
}

function chartPoints(history) {
  const uuid = document.querySelector('#trend-player').value;
  return (history?.hours || []).map(hour => {
    const traffic = uuid === '__server' ? hour.totals : (hour.players || []).find(player => player.playerUuid === uuid);
    const outbound = Number(traffic?.outboundWireBytes || 0);
    const inbound = Number(traffic?.inboundWireBytes || 0);
    return { time: hour.periodStartMillis, outbound, inbound, total: outbound + inbound };
  });
}

function drawChart(history) {
  const points = chartPoints(history);
  const rect = chartNode.getBoundingClientRect();
  if (!rect.width || !rect.height) return;
  const ratio = Math.min(2, window.devicePixelRatio || 1);
  chartNode.width = Math.round(rect.width * ratio);
  chartNode.height = Math.round(rect.height * ratio);
  const context = chartNode.getContext('2d');
  context.scale(ratio, ratio);
  const style = getComputedStyle(document.documentElement);
  const colors = { total: style.getPropertyValue('--total').trim(), outbound: style.getPropertyValue('--outbound').trim(), inbound: style.getPropertyValue('--inbound').trim() };
  const text = style.getPropertyValue('--muted').trim();
  const grid = style.getPropertyValue('--border').trim();
  const surface = style.getPropertyValue('--surface-raised').trim();
  const foreground = style.getPropertyValue('--text').trim();
  const width = rect.width;
  const height = rect.height;
  const pad = { left: 62, right: 18, top: 14, bottom: 34 };
  const innerWidth = Math.max(1, width - pad.left - pad.right);
  const innerHeight = Math.max(1, height - pad.top - pad.bottom);
  const active = [...visibleSeries];
  const maximum = Math.max(1, ...points.flatMap(point => active.map(series => point[series])));
  context.clearRect(0, 0, width, height);
  context.font = '11px Segoe UI, sans-serif';
  context.textBaseline = 'middle';
  for (let i = 0; i <= 4; i++) {
    const y = pad.top + innerHeight * i / 4;
    context.strokeStyle = grid;
    context.lineWidth = 1;
    context.beginPath(); context.moveTo(pad.left, y); context.lineTo(width - pad.right, y); context.stroke();
    context.fillStyle = text; context.textAlign = 'right'; context.fillText(bytes(maximum * (4 - i) / 4), pad.left - 8, y);
  }
  const x = index => pad.left + (points.length <= 1 ? 0 : innerWidth * index / (points.length - 1));
  const y = value => pad.top + innerHeight - innerHeight * value / maximum;
  const tickCount = Math.min(5, points.length);
  for (let i = 0; i < tickCount; i++) {
    const index = Math.round((points.length - 1) * i / Math.max(1, tickCount - 1));
    context.fillStyle = text; context.textAlign = i === 0 ? 'left' : i === tickCount - 1 ? 'right' : 'center';
    context.fillText(formatChartHour(points[index]?.time), x(index), height - 14);
  }
  active.forEach(series => {
    context.strokeStyle = colors[series]; context.lineWidth = 2; context.lineJoin = 'round'; context.beginPath();
    points.forEach((point, index) => index === 0 ? context.moveTo(x(index), y(point[series])) : context.lineTo(x(index), y(point[series])));
    context.stroke();
  });
  if (chartHoverIndex >= 0 && chartHoverIndex < points.length) {
    const point = points[chartHoverIndex];
    const hoverX = x(chartHoverIndex);
    context.strokeStyle = text; context.lineWidth = 1; context.beginPath(); context.moveTo(hoverX, pad.top); context.lineTo(hoverX, pad.top + innerHeight); context.stroke();
    active.forEach(series => { context.fillStyle = colors[series]; context.beginPath(); context.arc(hoverX, y(point[series]), 3.5, 0, Math.PI * 2); context.fill(); });
    const lines = [formatHour(point.time), ...active.map(series => `${t(series)}  ${bytes(point[series])}`)];
    const boxWidth = Math.max(...lines.map(line => context.measureText(line).width)) + 24;
    const boxHeight = lines.length * 20 + 10;
    const boxX = hoverX + boxWidth + 16 > width ? hoverX - boxWidth - 10 : hoverX + 10;
    const boxY = pad.top + 6;
    context.fillStyle = surface; context.fillRect(boxX, boxY, boxWidth, boxHeight);
    context.strokeStyle = grid; context.strokeRect(boxX, boxY, boxWidth, boxHeight);
    lines.forEach((line, index) => { context.fillStyle = index === 0 ? foreground : colors[active[index - 1]]; context.textAlign = 'left'; context.fillText(line, boxX + 12, boxY + 15 + index * 20); });
  }
}

function renderMonth(players, history) {
  const rows = [...players].sort((a, b) => totalWire(b.traffic) - totalWire(a.traffic));
  document.querySelector('#month-period').textContent = history ? `${formatDate(history.periodStartMillis)} - ${formatDate(history.periodEndMillis - 1)}` : '';
  document.querySelector('#month-count').textContent = template('records', { count: rows.length });
  const slice = pageSlice(rows, pages.month);
  const maximum = Math.max(1, ...rows.map(player => totalWire(player.traffic)));
  document.querySelector('#month-players').innerHTML = slice.map(player => `<div class="ranking-row"><div class="ranking-player"><strong>${escapeText(player.playerName)}</strong><span class="uuid">${escapeText(player.playerUuid)}</span></div><div class="ranking-main"><meter class="ranking-bar" min="0" max="100" value="${Math.round(totalWire(player.traffic) * 100 / maximum)}"></meter><div class="ranking-values"><span><strong>${bytes(totalWire(player.traffic))}</strong><small>${escapeText(t('total'))}</small></span><span><strong>${bytes(player.traffic.outboundWireBytes)}</strong><small>${escapeText(t('outbound'))}</small></span><span><strong>${bytes(player.traffic.inboundWireBytes)}</strong><small>${escapeText(t('inbound'))}</small></span></div></div></div>`).join('') || `<div class="empty">${escapeText(t('noData'))}</div>`;
  renderPagination('month', rows.length, () => renderMonth(players, history));
}

function setActiveView(view, updateHash = true) {
  activeView = ['overview', 'players', 'details'].includes(view) ? view : 'overview';
  document.querySelectorAll('.view-page').forEach(node => { node.hidden = node.id !== `view-${activeView}`; });
  document.querySelectorAll('#view-tabs button').forEach(button => button.setAttribute('aria-pressed', `${button.dataset.view === activeView}`));
  if (updateHash) history.replaceState(null, '', `#${activeView}`);
  if (activeView === 'overview' && reportBundle?.trafficHistory) requestAnimationFrame(() => drawChart(reportBundle.trafficHistory));
}

function renderHourly(history) {
  const uuid = document.querySelector('#hourly-player').value;
  const rows = (history?.hours || []).map(hour => {
    const player = (hour.players || []).find(entry => entry.playerUuid === uuid);
    const outbound = Number(player?.outboundWireBytes || 0);
    const inbound = Number(player?.inboundWireBytes || 0);
    return { hour, outbound, inbound, total: outbound + inbound };
  }).reverse();
  document.querySelector('#hourly-count').textContent = template('records', { count: rows.length });
  const maximum = Math.max(1, ...rows.map(row => row.total));
  const slice = pageSlice(rows, pages.hourly);
  document.querySelector('#hourly-rows').innerHTML = slice.map(row => `<tr><td>${formatHour(row.hour.periodStartMillis)}</td><td><meter class="traffic-bar" min="0" max="100" value="${Math.round(row.total * 100 / maximum)}"></meter>${bytes(row.total)}</td><td>${bytes(row.outbound)}</td><td>${bytes(row.inbound)}</td><td><span class="state ${row.hour.complete ? 'complete' : 'partial'}">${row.hour.complete ? t('complete') : t('partial')}</span></td></tr>`).join('') || emptyRow(5);
  renderPagination('hourly', rows.length, () => renderHourly(history));
}

function renderCurrent(players) {
  const rows = [...players].sort((a, b) => totalWire(b.traffic) - totalWire(a.traffic));
  document.querySelector('#current-count').textContent = template('records', { count: rows.length });
  const slice = pageSlice(rows, pages.current);
  document.querySelector('#current-players').innerHTML = slice.map(player => `<tr><td>${escapeText(player.playerName)}<span class="uuid">${escapeText(player.playerUuid)}</span></td><td>${bytes(player.traffic.outboundWireBytes)}</td><td>${bytes(player.traffic.inboundWireBytes)}</td><td>${bytes(player.traffic.outboundRawBytes)}</td><td>${bytes(player.traffic.outboundTransportBytes)}</td><td>${bytes(player.traffic.outboundBypassBytes)}</td></tr>`).join('') || emptyRow(6);
  renderPagination('current', rows.length, () => renderCurrent(players));
}

function renderDetails(sections) {
  document.querySelector('#sections').innerHTML = sections.map(section => `<div class="section"><h3>${escapeText(t('sections')[section.id] || section.title)}</h3><p>${escapeText(t('descriptions')[section.id] || section.description)}</p><div class="table-wrap"><table><thead><tr><th>${t('metric')}</th><th>${t('value')}</th><th>${t('stage')}</th><th>${t('scope')}</th></tr></thead><tbody>${section.metrics.map(item => `<tr><td>${escapeText(metricLabel(item))}</td><td>${escapeText(metricValue(item))}</td><td>${escapeText(t('stages')[item.stage] || item.stage)}</td><td>${escapeText(t('scopes')[item.scope] || item.scope)}</td></tr>`).join('')}</tbody></table></div></div>`).join('');
}

function renderPagination(name, count, rerender) {
  const state = pages[name];
  const pageCount = Math.max(1, Math.ceil(count / state.size));
  state.page = Math.min(state.page, pageCount);
  const node = document.querySelector(`#${name}-pagination`);
  node.innerHTML = `<span class="page-size-label">${escapeText(t('rowsPerPage'))}</span><select aria-label="${escapeAttribute(t('rowsPerPage'))}">${[10, 25, 50].map(size => `<option value="${size}"${size === state.size ? ' selected' : ''}>${size}</option>`).join('')}</select><button type="button" class="page-prev" title="${escapeAttribute(t('previous'))}" aria-label="${escapeAttribute(t('previous'))}"${state.page <= 1 ? ' disabled' : ''}>‹</button><span class="page-indicator">${escapeText(template('page', { page: state.page, pages: pageCount }))}</span><button type="button" class="page-next" title="${escapeAttribute(t('next'))}" aria-label="${escapeAttribute(t('next'))}"${state.page >= pageCount ? ' disabled' : ''}>›</button>`;
  node.querySelector('select').addEventListener('change', event => { state.size = Number(event.target.value); state.page = 1; rerender(); });
  node.querySelector('.page-prev').addEventListener('click', () => { state.page--; rerender(); });
  node.querySelector('.page-next').addEventListener('click', () => { state.page++; rerender(); });
}

function pageSlice(rows, state) { const start = (state.page - 1) * state.size; return rows.slice(start, start + state.size); }
function metricLabel(item) { const short = item.id.split('.').pop(); return metricLabels[language]?.[item.id] || metricLabels[language]?.[short] || item.label; }
function metricValue(item) { if (item.unit === 'bytes') return bytes(item.value); if (item.unit === 'boolean') return Number(item.value) ? t('enabled') : t('disabled'); return `${item.value} ${t('units')[item.unit] || item.unit}`; }
function totalWire(traffic) { return Number(traffic?.outboundWireBytes || 0) + Number(traffic?.inboundWireBytes || 0); }
function emptyRow(columns) { return `<tr><td colspan="${columns}" class="empty">${escapeText(t('noData'))}</td></tr>`; }
function formatDate(value) { return new Intl.DateTimeFormat(language, { dateStyle: 'medium' }).format(new Date(value)); }
function formatDateTime(value) { return new Intl.DateTimeFormat(language, { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value)); }
function formatHour(value) { return new Intl.DateTimeFormat(language, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value)); }
function formatChartHour(value) { return value == null ? '' : new Intl.DateTimeFormat(language, { month: '2-digit', day: '2-digit', hour: '2-digit' }).format(new Date(value)); }
function escapeText(value) { const node = document.createElement('span'); node.textContent = value == null ? '' : String(value); return node.innerHTML; }
function escapeAttribute(value) { return String(value == null ? '' : value).replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll("'", '&#39;').replaceAll('<', '&lt;').replaceAll('>', '&gt;'); }

languageNode.addEventListener('change', () => { language = languageNode.value; localStorage.setItem('bostats.language', language); reportBundle ? render() : setStatus(statusKey, !retryNode.hidden); });
document.querySelectorAll('#view-tabs button').forEach(button => button.addEventListener('click', () => setActiveView(button.dataset.view)));
window.addEventListener('hashchange', () => setActiveView(location.hash.slice(1), false));
document.querySelector('#trend-player').addEventListener('change', () => { chartHoverIndex = -1; renderTrend(reportBundle?.trafficHistory); });
document.querySelector('#hourly-player').addEventListener('change', () => { pages.hourly.page = 1; renderHourly(reportBundle?.trafficHistory); });
chartNode.addEventListener('pointermove', event => {
  const points = chartPoints(reportBundle?.trafficHistory);
  const rect = chartNode.getBoundingClientRect();
  const innerWidth = Math.max(1, rect.width - 80);
  chartHoverIndex = Math.max(0, Math.min(points.length - 1, Math.round((event.clientX - rect.left - 62) * Math.max(0, points.length - 1) / innerWidth)));
  drawChart(reportBundle?.trafficHistory);
});
chartNode.addEventListener('pointerleave', () => { chartHoverIndex = -1; drawChart(reportBundle?.trafficHistory); });
new ResizeObserver(() => { if (reportBundle?.trafficHistory) drawChart(reportBundle.trafficHistory); }).observe(chartNode);
retryNode.addEventListener('click', () => load().catch(showLoadError));
load().catch(showLoadError);
