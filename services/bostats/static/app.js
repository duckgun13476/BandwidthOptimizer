const translations = {
  'zh-CN': {
    language: '语言', loading: '正在读取报告...', invalidLink: '请打开一条完整的报告链接。', retry: '重试',
    missing: '报告不存在或已过期。', failed: '读取报告失败。', timedOut: '读取报告超时。', title: '带宽报告',
    subtitle: '服务器带宽使用与优化结果', trend: '小时流量趋势', dataRange: '数据范围', serverTotal: '全服总量',
    tabOverview: '总览', tabPlayers: '玩家', tabDetails: '传输详情', tabPackets: '包分析', pageNavigation: '报告页面', detailsSubtitle: '各压缩阶段与运行状态', packetsSubtitle: '旁路来源与包级流量明细', transportFlow: '传输管线', nativeReference: '原版估算参考', packetStructure: '包与映射构成', wireEfficiency: '线路效率', measuredWire: '实际线路构成', avoidedTraffic: '缓存与门控节省', hotspotEfficiency: '热点区块效率', memoryFootprint: '当前内存占用对比', cacheReuse: '区块缓存复用', diagnosticsEnabled: '项诊断已开启',
    month: '月度玩家排行', current: '当前小时排行', details: '传输详情', player: '玩家', hour: '小时',
    totalWire: '实际总流量', outboundWire: '出站实际', inboundWire: '入站实际', rawOutbound: '原始出站', boFrame: 'BO 帧',
    bypass: '旁路', status: '状态', complete: '已完成', partial: '进行中', noData: '暂无流量数据', sessionOutbound: '本次出站实际',
    sessionInbound: '本次入站实际', monthTotal: '本月实际总流量', monthOutbound: '本月出站实际', monthInbound: '本月入站实际',
    players: '本月玩家', metric: '指标', value: '值', stage: '阶段', scope: '范围', total: '总流量', outbound: '出站', inbound: '入站',
    records: '{count} 条记录', page: '第 {page} / {pages} 页', rowsPerPage: '每页', previous: '上一页', next: '下一页',
    enabled: '已开启', disabled: '已关闭', chartLabel: '按小时统计的实际线路流量曲线', rankingLabel: '按实际总流量从高到低排列的玩家',
    bypassAnalysis: '旁路流量分析', bypassSubtitle: '按来源与包路径显示绕过 BO 传输层的流量', bypassSources: '来源排行', bypassEntries: '包级明细', capturedKeys: '已上传键', distinctKeys: '不同键', cumulativeTraffic: '累计流量', windowTraffic: '当前窗口', unlistedKeys: '未列出的键',
    uploadTitle: '上传带宽报告', uploadSubtitle: '选择 BandwidthOptimizer 生成的报告文件', uploadPrompt: '将报告拖到这里', uploadChoice: '或选择文件', uploadHint: 'JSON 或 Gzip，最大 4 MiB', noFile: '尚未选择文件', uploadReport: '上传报告', uploading: '正在上传并校验报告…', invalidReport: '这不是可用的 BO 报告文件。', reportTooLarge: '报告解压后不能超过 4 MiB。', uploadFailed: '上传失败，请稍后重试。',
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
    tabOverview: 'Overview', tabPlayers: 'Players', tabDetails: 'Transport details', tabPackets: 'Packet analysis', pageNavigation: 'Report pages', detailsSubtitle: 'Compression stages and runtime state', packetsSubtitle: 'Bypass sources and packet-level traffic details', transportFlow: 'Transport pipeline', nativeReference: 'Vanilla estimate reference', packetStructure: 'Packet and mapping composition', wireEfficiency: 'Wire efficiency', measuredWire: 'Measured wire composition', avoidedTraffic: 'Cache and gate savings', hotspotEfficiency: 'Hotspot chunk efficiency', memoryFootprint: 'Current memory footprint', cacheReuse: 'Chunk cache reuse', diagnosticsEnabled: 'diagnostics enabled',
    month: 'Monthly player ranking', current: 'Current-hour ranking', details: 'Transport details', player: 'Player', hour: 'Hour',
    totalWire: 'Measured total', outboundWire: 'Outbound measured', inboundWire: 'Inbound measured', rawOutbound: 'Outbound raw', boFrame: 'BO frames',
    bypass: 'Bypass', status: 'Status', complete: 'Complete', partial: 'In progress', noData: 'No traffic data', sessionOutbound: 'Session outbound',
    sessionInbound: 'Session inbound', monthTotal: 'Monthly measured total', monthOutbound: 'Monthly outbound', monthInbound: 'Monthly inbound',
    players: 'Monthly players', metric: 'Metric', value: 'Value', stage: 'Stage', scope: 'Scope', total: 'Total', outbound: 'Outbound', inbound: 'Inbound',
    records: '{count} records', page: 'Page {page} of {pages}', rowsPerPage: 'Rows', previous: 'Previous page', next: 'Next page',
    enabled: 'Enabled', disabled: 'Disabled', chartLabel: 'Measured wire traffic by hour', rankingLabel: 'Players ranked by measured total traffic',
    bypassAnalysis: 'Bypass traffic analysis', bypassSubtitle: 'Traffic that bypassed the BO transport, grouped by source and packet path', bypassSources: 'Source ranking', bypassEntries: 'Packet details', capturedKeys: 'Uploaded keys', distinctKeys: 'Distinct keys', cumulativeTraffic: 'Cumulative traffic', windowTraffic: 'Current window', unlistedKeys: 'Unlisted keys',
    uploadTitle: 'Upload bandwidth report', uploadSubtitle: 'Select a report generated by BandwidthOptimizer', uploadPrompt: 'Drop a report here', uploadChoice: 'or choose a file', uploadHint: 'JSON or Gzip, up to 4 MiB', noFile: 'No file selected', uploadReport: 'Upload report', uploading: 'Uploading and validating report…', invalidReport: 'This is not a valid BO report file.', reportTooLarge: 'The decompressed report must not exceed 4 MiB.', uploadFailed: 'Upload failed. Try again later.',
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
    tabOverview: 'Visão geral', tabPlayers: 'Jogadores', tabDetails: 'Detalhes do transporte', tabPackets: 'Análise de pacotes', pageNavigation: 'Páginas do relatório', detailsSubtitle: 'Etapas de compressão e estado de execução', packetsSubtitle: 'Origens de desvio e detalhes de tráfego por pacote', transportFlow: 'Pipeline de transporte', nativeReference: 'Referência da estimativa vanilla', packetStructure: 'Composição de pacotes e mapeamento', wireEfficiency: 'Eficiência da rede', measuredWire: 'Composição do tráfego medido', avoidedTraffic: 'Economia de cache e gates', hotspotEfficiency: 'Eficiência dos chunks hotspot', memoryFootprint: 'Uso atual de memória', cacheReuse: 'Reuso do cache de chunks', diagnosticsEnabled: 'diagnósticos ativos',
    month: 'Ranking mensal de jogadores', current: 'Ranking da hora atual', details: 'Detalhes do transporte', player: 'Jogador', hour: 'Hora',
    totalWire: 'Total medido', outboundWire: 'Saída medida', inboundWire: 'Entrada medida', rawOutbound: 'Saída original', boFrame: 'Quadros BO',
    bypass: 'Desvio', status: 'Estado', complete: 'Concluído', partial: 'Em andamento', noData: 'Sem dados de tráfego', sessionOutbound: 'Saída da sessão',
    sessionInbound: 'Entrada da sessão', monthTotal: 'Total mensal medido', monthOutbound: 'Saída mensal', monthInbound: 'Entrada mensal',
    players: 'Jogadores no mês', metric: 'Métrica', value: 'Valor', stage: 'Etapa', scope: 'Escopo', total: 'Total', outbound: 'Saída', inbound: 'Entrada',
    records: '{count} registros', page: 'Página {page} de {pages}', rowsPerPage: 'Linhas', previous: 'Página anterior', next: 'Próxima página',
    enabled: 'Ativado', disabled: 'Desativado', chartLabel: 'Tráfego medido por hora', rankingLabel: 'Jogadores classificados pelo tráfego total medido',
    bypassAnalysis: 'Análise do tráfego desviado', bypassSubtitle: 'Tráfego fora do transporte BO, agrupado por origem e caminho do pacote', bypassSources: 'Ranking por origem', bypassEntries: 'Detalhes dos pacotes', capturedKeys: 'Chaves enviadas', distinctKeys: 'Chaves distintas', cumulativeTraffic: 'Tráfego acumulado', windowTraffic: 'Janela atual', unlistedKeys: 'Chaves não listadas',
    uploadTitle: 'Enviar relatório de largura de banda', uploadSubtitle: 'Selecione um relatório gerado pelo BandwidthOptimizer', uploadPrompt: 'Solte um relatório aqui', uploadChoice: 'ou escolha um arquivo', uploadHint: 'JSON ou Gzip, até 4 MiB', noFile: 'Nenhum arquivo selecionado', uploadReport: 'Enviar relatório', uploading: 'Enviando e validando o relatório…', invalidReport: 'Este não é um relatório BO válido.', reportTooLarge: 'O relatório descompactado não pode exceder 4 MiB.', uploadFailed: 'Falha no envio. Tente novamente mais tarde.',
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
const pages = { bypassSources: { page: 1, size: 10 }, bypassEntries: { page: 1, size: 10 } };
let activeView = ['details', 'packets'].includes(location.hash.slice(1)) ? location.hash.slice(1) : 'overview';
if (location.hash === '#players') history.replaceState(null, '', '#overview');
let chartHoverIndex = -1;
const t = key => translations[language][key] || translations['en-US'][key] || key;
const template = (key, values) => Object.entries(values).reduce((text, [name, value]) => text.replace(`{${name}}`, value), t(key));
const statusNode = document.querySelector('#status');
const statusTextNode = document.querySelector('#status-text');
const retryNode = document.querySelector('#retry');
const chartNode = document.querySelector('#traffic-chart');
const uploadViewNode = document.querySelector('#view-upload');
const uploadDropNode = document.querySelector('#upload-drop');
const uploadFileNode = document.querySelector('#upload-file');
const uploadSubmitNode = document.querySelector('#upload-submit');
const uploadStatusNode = document.querySelector('#upload-status');
const rankingTooltipNode = document.querySelector('#ranking-tooltip');
let uploadFile = null;
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
  if (location.pathname === '/' || location.pathname === '/report' || location.pathname === '/report/') { renderUpload(); return; }
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

function renderUpload() {
  document.documentElement.lang = language;
  document.title = `BO Stats - ${t('uploadTitle')}`;
  document.querySelector('#language-label').textContent = t('language');
  languageNode.setAttribute('aria-label', t('language'));
  document.querySelector('#view-tabs').hidden = true;
  document.querySelector('#generated').hidden = true;
  document.querySelector('#report-id').textContent = '';
  document.querySelector('#status').hidden = true;
  document.querySelectorAll('.view-page').forEach(node => { node.hidden = true; });
  uploadViewNode.hidden = false;
  document.querySelector('#upload-title').textContent = t('uploadTitle');
  document.querySelector('#upload-subtitle').textContent = t('uploadSubtitle');
  document.querySelector('#upload-prompt').textContent = t('uploadPrompt');
  document.querySelector('#upload-choice').textContent = t('uploadChoice');
  document.querySelector('#upload-hint').textContent = t('uploadHint');
  document.querySelector('#upload-file-name').textContent = uploadFile?.name || t('noFile');
  document.querySelector('#upload-file-size').textContent = uploadFile ? bytes(uploadFile.size) : '';
  uploadSubmitNode.textContent = t('uploadReport');
  uploadSubmitNode.disabled = !uploadFile;
}

function selectUploadFile(file) {
  uploadFile = file || null;
  uploadStatusNode.hidden = true;
  renderUpload();
}

async function decodedUploadBytes(file) {
  let data = await file.arrayBuffer();
  const bytesView = new Uint8Array(data);
  if (bytesView[0] === 0x1f && bytesView[1] === 0x8b) {
    if (typeof DecompressionStream !== 'function') throw new Error('invalidReport');
    data = await new Response(new Blob([data]).stream().pipeThrough(new DecompressionStream('gzip'))).arrayBuffer();
  }
  if (data.byteLength > 4 * 1024 * 1024) throw new Error('reportTooLarge');
  return data;
}

async function uploadSelectedReport() {
  if (!uploadFile) return;
  uploadSubmitNode.disabled = true;
  uploadStatusNode.textContent = t('uploading');
  uploadStatusNode.className = 'upload-status';
  uploadStatusNode.hidden = false;
  try {
    const data = await decodedUploadBytes(uploadFile);
    const report = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(data));
    if (report?.schemaVersion !== 1 || typeof report.reportId !== 'string' || !report.summary || !report.playerTraffic) throw new Error('invalidReport');
    const response = await fetch('/api/v1/reports', { method: 'POST', headers: { 'Content-Type': 'application/vnd.bandwidthoptimizer.report-bundle+json' }, body: data });
    const result = await response.json().catch(() => ({}));
    if (!response.ok || !result.url) throw new Error(result.error || 'uploadFailed');
    location.assign(result.url);
  } catch (error) {
    const key = ['invalidReport', 'reportTooLarge'].includes(error?.message) ? error.message : 'uploadFailed';
    uploadStatusNode.textContent = t(key);
    uploadStatusNode.className = 'upload-status error';
    uploadSubmitNode.disabled = false;
  }
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
  hideRankingTooltip();
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
  document.querySelector('[data-view="details"]').textContent = t('tabDetails');
  document.querySelector('[data-view="packets"]').textContent = t('tabPackets');
  document.querySelector('#trend-title').textContent = t('trend');
  document.querySelector('#trend-player-label').textContent = t('dataRange');
  document.querySelector('#month-title').textContent = t('month');
  document.querySelector('#current-title').textContent = t('current');
  document.querySelector('#details-title').textContent = t('details');
  document.querySelector('#details-subtitle').textContent = t('detailsSubtitle');
  document.querySelector('#packets-title').textContent = t('tabPackets');
  document.querySelector('#packets-subtitle').textContent = t('packetsSubtitle');
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
  renderCurrent(traffic?.players || [], traffic);
  renderDetails(summary.sections || []);
  renderBypass(summary.bypass);

  statusNode.hidden = true;
  document.querySelector('#trend-section').hidden = !history;
  setActiveView(activeView, false);
}

function populatePlayerSelectors(players) {
  const sorted = [...players].sort((a, b) => a.playerName.localeCompare(b.playerName, language));
  const trend = document.querySelector('#trend-player');
  const trendPrevious = trend.value || '__server';
  trend.innerHTML = `<option value="__server">${escapeText(t('serverTotal'))}</option>${playerOptions(sorted)}`;
  trend.value = sorted.some(player => player.playerUuid === trendPrevious) ? trendPrevious : '__server';
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
  renderPlayerRanking('month', rows);
}

function setActiveView(view, updateHash = true) {
  activeView = ['overview', 'details', 'packets'].includes(view) ? view : 'overview';
  document.querySelectorAll('.view-page').forEach(node => { node.hidden = node.id !== `view-${activeView}`; });
  document.querySelectorAll('#view-tabs button').forEach(button => button.setAttribute('aria-pressed', `${button.dataset.view === activeView}`));
  if (updateHash) history.replaceState(null, '', `#${activeView}`);
  if (activeView === 'overview' && reportBundle?.trafficHistory) requestAnimationFrame(() => drawChart(reportBundle.trafficHistory));
}

function renderCurrent(players, traffic) {
  const rows = [...players].sort((a, b) => totalWire(b.traffic) - totalWire(a.traffic));
  document.querySelector('#current-period').textContent = traffic ? `${formatHour(traffic.periodStartMillis)} - ${formatHour(traffic.periodEndMillis)}` : '';
  renderPlayerRanking('current', rows);
}

function renderPlayerRanking(name, players) {
  const target = document.querySelector(`#${name}-players`);
  document.querySelector(`#${name}-count`).textContent = new Intl.NumberFormat(language).format(players.length);
  target.setAttribute('aria-label', t('rankingLabel'));
  const maximum = Math.max(1, ...players.map(player => totalWire(player.traffic)));
  target.innerHTML = players.map((player, index) => {
    const traffic = player.traffic || {};
    const outbound = Math.max(0, Number(traffic.outboundWireBytes || 0));
    const inbound = Math.max(0, Number(traffic.inboundWireBytes || 0));
    const total = outbound + inbound;
    const outboundWidth = outbound * 100 / maximum;
    const inboundWidth = inbound * 100 / maximum;
    const outboundTip = `${t('outbound')} ${bytes(outbound)}`;
    const inboundTip = `${t('inbound')} ${bytes(inbound)}`;
    return `<div class="player-ranking-row"><span class="rank-number">${index + 1}</span><svg class="ranking-bar" viewBox="0 0 100 1" preserveAspectRatio="none" role="img" aria-label="${escapeAttribute(`${player.playerName} ${bytes(total)}`)}"><rect class="ranking-bar-track" width="100" height="1"></rect><rect class="ranking-bar-outbound" width="${outboundWidth}" height="1" tabindex="0" data-direction="outbound" data-tooltip="${escapeAttribute(outboundTip)}" aria-label="${escapeAttribute(outboundTip)}"></rect><rect class="ranking-bar-inbound" x="${outboundWidth}" width="${inboundWidth}" height="1" tabindex="0" data-direction="inbound" data-tooltip="${escapeAttribute(inboundTip)}" aria-label="${escapeAttribute(inboundTip)}"></rect></svg><div class="ranking-player"><strong>${escapeText(player.playerName)}</strong></div></div>`;
  }).join('') || `<div class="empty">${escapeText(t('noData'))}</div>`;
}

function rankingSegment(target) {
  return target?.closest?.('.ranking-bar-outbound, .ranking-bar-inbound');
}

function showRankingTooltip(segment, clientX, clientY) {
  rankingTooltipNode.textContent = segment.dataset.tooltip || '';
  rankingTooltipNode.dataset.direction = segment.dataset.direction || '';
  rankingTooltipNode.hidden = false;
  const bounds = rankingTooltipNode.getBoundingClientRect();
  const left = Math.max(8, Math.min(clientX + 12, window.innerWidth - bounds.width - 8));
  const top = Math.max(8, clientY - bounds.height - 12);
  rankingTooltipNode.style.left = `${left}px`;
  rankingTooltipNode.style.top = `${top}px`;
}

function hideRankingTooltip() {
  rankingTooltipNode.hidden = true;
}

function renderDetails(sections) {
  const renderers = { transport: renderTransportDetails, server: renderServerDetails, chunk: renderChunkDetails, diagnostics: renderDiagnosticDetails };
  document.querySelector('#sections').innerHTML = sections.map(section => (renderers[section.id] || renderGenericDetails)(section)).join('');
}

function detailSection(section, content, extraClass = '') {
  const title = t('sections')[section.id] || section.title;
  const description = t('descriptions')[section.id] || section.description;
  return `<section class="detail-band ${extraClass}"><div class="detail-heading"><div><h3>${escapeText(title)}</h3><p>${escapeText(description)}</p></div></div>${content}</section>`;
}

function sectionMetrics(section) {
  return Object.fromEntries((section.metrics || []).map(item => [item.id, item]));
}

function renderTransportDetails(section) {
  const metrics = sectionMetrics(section);
  const lanes = ['outbound', 'inbound'].map(scope => renderTransportLane(scope, metrics)).join('');
  return detailSection(section, `<div class="transport-lanes">${lanes}</div>`, 'transport-detail');
}

function renderTransportLane(scope, metrics) {
  const ids = ['baseline_bytes', 'mapping_bytes', 'zstd_body_bytes', 'bo_frame_bytes'];
  const stages = ids.map(id => metrics[`${scope}.${id}`]).filter(Boolean);
  const baseline = Number(metrics[`${scope}.baseline_bytes`]?.value || 0);
  const maximum = Math.max(1, ...stages.map(item => Number(item.value || 0)));
  const stageRows = stages.map(item => {
    const value = Number(item.value || 0);
    const width = value * 100 / maximum;
    const relative = baseline > 0 ? value * 100 / baseline : 0;
    return `<div class="pipeline-stage"><div><small>${escapeText(t('stages')[item.stage] || metricLabel(item))}</small><strong>${escapeText(metricValue(item))}</strong></div><meter min="0" max="100" value="${width.toFixed(3)}"></meter><span>${relative.toFixed(1)}%</span></div>`;
  }).join('');
  const vanilla = metrics[`${scope}.vanilla_estimate_bytes`];
  const counters = ['packets', 'frames', 'bypass_bytes', 'bypass_packets'].map(id => metrics[`${scope}.${id}`]).filter(Boolean);
  const mappings = ['literal_entries', 'exact_refs', 'template_refs'].map(id => metrics[`${scope}.${id}`]).filter(Boolean);
  return `<article class="pipeline-lane scope-${scope}"><div class="pipeline-lane-heading"><h4>${escapeText(t(scope))}</h4><span>${escapeText(t('transportFlow'))}</span></div><div class="pipeline-stages">${stageRows}</div><div class="pipeline-reference"><span>${escapeText(t('nativeReference'))}</span><strong>${escapeText(vanilla ? metricValue(vanilla) : bytes(0))}</strong></div><div class="pipeline-facts"><div><h5>${escapeText(t('packetStructure'))}</h5>${counters.map(item => `<span><small>${escapeText(metricLabel(item))}</small><strong>${escapeText(metricValue(item))}</strong></span>`).join('')}</div><div><h5>${escapeText(t('stages').mapping)}</h5>${mappings.map(item => `<span><small>${escapeText(metricLabel(item))}</small><strong>${escapeText(metricValue(item))}</strong></span>`).join('')}</div></div></article>`;
}

function renderServerDetails(section) {
  const metrics = sectionMetrics(section);
  const raw = metrics.outbound_raw_bytes;
  const vanilla = metrics.outbound_vanilla_estimate_bytes;
  const outbound = metrics.outbound_wire_bytes;
  const inbound = metrics.inbound_wire_bytes;
  const comparison = [raw, vanilla, outbound].filter(Boolean);
  const comparisonMaximum = Math.max(1, ...comparison.map(item => Number(item.value || 0)));
  const savings = ['outbound_saved_bytes', 'offline_reuse_saved_bytes', 'temporary_reuse_saved_bytes', 'create_gate_saved_bytes', 'idle_gate_saved_bytes'].map(id => metrics[id]).filter(Boolean);
  const savingsMaximum = Math.max(1, ...savings.map(item => Number(item.value || 0)));
  const active = metrics.active_channels;
  const players = metrics.bound_players;
  const observed = metrics.create_gate_observed_bytes;
  return detailSection(section, `<div class="server-detail-grid"><div class="detail-cluster wire-efficiency"><div class="cluster-heading"><h4>${escapeText(t('wireEfficiency'))}</h4><div class="connection-facts"><span><strong>${escapeText(active ? metricValue(active) : '0')}</strong><small>${escapeText(active ? metricLabel(active) : t('units').channels)}</small></span><span><strong>${escapeText(players ? metricValue(players) : '0')}</strong><small>${escapeText(players ? metricLabel(players) : t('units').players)}</small></span></div></div>${comparison.map(item => renderComparisonMetric(item, comparisonMaximum)).join('')}</div><div class="detail-cluster measured-wire"><div class="cluster-heading"><h4>${escapeText(t('measuredWire'))}</h4></div>${renderWireComposition(outbound, inbound)}</div><div class="detail-cluster avoided-traffic"><div class="cluster-heading"><h4>${escapeText(t('avoidedTraffic'))}</h4>${observed ? `<span>${escapeText(metricLabel(observed))}: ${escapeText(metricValue(observed))}</span>` : ''}</div>${savings.map(item => renderComparisonMetric(item, savingsMaximum)).join('')}</div></div>`, 'server-detail');
}

function renderWireComposition(outbound, inbound) {
  const outboundValue = Number(outbound?.value || 0);
  const inboundValue = Number(inbound?.value || 0);
  const measuredTotal = outboundValue + inboundValue;
  const scale = Math.max(1, measuredTotal);
  const outboundWidth = outboundValue * 100 / scale;
  const inboundWidth = inboundValue * 100 / scale;
  return `<div class="wire-composition"><svg viewBox="0 0 100 1" preserveAspectRatio="none" aria-label="${escapeAttribute(`${t('measuredWire')} ${bytes(measuredTotal)}`)}"><rect class="wire-outbound" width="${outboundWidth}" height="1"></rect><rect class="wire-inbound" x="${outboundWidth}" width="${inboundWidth}" height="1"></rect></svg><div class="wire-legend"><span class="outbound"><small>${escapeText(t('outbound'))}</small><strong>${escapeText(outbound ? metricValue(outbound) : bytes(0))}</strong><em>${outboundWidth.toFixed(1)}%</em></span><span class="inbound"><small>${escapeText(t('inbound'))}</small><strong>${escapeText(inbound ? metricValue(inbound) : bytes(0))}</strong><em>${inboundWidth.toFixed(1)}%</em></span></div></div>`;
}

function renderChunkDetails(section) {
  const metrics = sectionMetrics(section);
  const hotspot = ['hotspot.outbound.logical_bytes', 'hotspot.outbound.frame_bytes', 'hotspot.inbound.logical_bytes'].map(id => metrics[id]).filter(Boolean);
  const memory = ['shadow.total_bytes', 'shadow.retained_original_bytes', 'runtime.total_bytes'].map(id => metrics[id]).filter(Boolean);
  const reuse = ['reuse.temporary_saved_bytes', 'reuse.offline_saved_bytes'].map(id => metrics[id]).filter(Boolean);
  return detailSection(section, `<div class="chunk-detail-grid">${renderMetricCluster(t('hotspotEfficiency'), hotspot)}${renderMetricCluster(t('memoryFootprint'), memory)}${renderMetricCluster(t('cacheReuse'), reuse)}</div>`, 'chunk-detail');
}

function renderMetricCluster(title, metrics) {
  const maximum = Math.max(1, ...metrics.map(item => Number(item.value || 0)));
  return `<div class="detail-cluster"><div class="cluster-heading"><h4>${escapeText(title)}</h4></div>${metrics.map(item => renderComparisonMetric(item, maximum)).join('')}</div>`;
}

function renderComparisonMetric(item, maximum) {
  const value = Number(item.value || 0);
  const width = value * 100 / maximum;
  return `<div class="comparison-metric"><div><span>${escapeText(metricLabel(item))}</span><strong>${escapeText(metricValue(item))}</strong></div><meter min="0" max="100" value="${width.toFixed(3)}"></meter></div>`;
}

function renderDiagnosticDetails(section) {
  const enabled = section.metrics.filter(item => Number(item.value) !== 0).length;
  const summary = `${enabled} / ${section.metrics.length} ${t('diagnosticsEnabled')}`;
  const content = `<div class="diagnostic-summary"><strong>${escapeText(summary)}</strong></div>${renderBooleanMetrics(section.metrics)}`;
  return detailSection(section, content, 'diagnostic-detail');
}

function renderGenericDetails(section) {
  const content = section.metrics.every(item => item.unit === 'boolean') ? renderBooleanMetrics(section.metrics) : renderMetricGroups(section.metrics);
  return detailSection(section, content);
}

function renderBypass(bypass) {
  const node = document.querySelector('#bypass-section');
  if (!bypass) { node.className = 'section bypass-section'; node.innerHTML = `<div class="empty">${escapeText(t('noData'))}</div>`; return; }
  const entries = [...(bypass.entries || [])].sort((a, b) => Number(b.totalBytes || 0) - Number(a.totalBytes || 0));
  const sources = aggregateBypassSources(entries, bypass);
  const sourceMaximum = Math.max(1, ...sources.map(source => source.bytes));
  const entryMaximum = Math.max(1, ...entries.map(entry => Number(entry.totalBytes || 0)));
  const sourceRows = pageSlice(sources, pages.bypassSources).map(source => `<div class="bypass-row"><div class="bypass-identity"><strong>${escapeText(source.name)}</strong><small>${new Intl.NumberFormat(language).format(source.keys)} ${escapeText(t('units').entries)}</small></div><div class="bypass-main"><meter class="visual-track" min="0" max="100" value="${(source.bytes * 100 / sourceMaximum).toFixed(3)}" aria-label="${escapeAttribute(`${source.name} ${bytes(source.bytes)}`)}"></meter><div class="bypass-values"><strong>${bytes(source.bytes)}</strong><small>${new Intl.NumberFormat(language).format(source.packets)} ${escapeText(t('units').packets)}</small></div></div></div>`).join('') || `<div class="empty">${escapeText(t('noData'))}</div>`;
  const entryRows = pageSlice(entries, pages.bypassEntries).map(entry => {
    const name = entry.payloadChannel || entry.packetClass?.split('.').pop() || `#${entry.rawPacketId}`;
    const detail = entry.payloadChannel ? entry.packetClass : `${entry.protocol} · ${entry.flow}`;
    return `<div class="bypass-row bypass-entry"><div class="bypass-identity"><strong>${escapeText(name)}</strong><small>${escapeText(detail)}</small><small>${escapeText(entry.reason)}</small></div><div class="bypass-main"><meter class="visual-track" min="0" max="100" value="${(Number(entry.totalBytes || 0) * 100 / entryMaximum).toFixed(3)}" aria-label="${escapeAttribute(`${name} ${bytes(entry.totalBytes)}`)}"></meter><div class="bypass-values"><strong>${bytes(entry.totalBytes)}</strong><small>${new Intl.NumberFormat(language).format(Number(entry.totalPackets || 0))} ${escapeText(t('units').packets)}</small><small>${bytes(entry.windowBytes)} · ${new Intl.NumberFormat(language).format(Number(entry.windowPackets || 0))}</small></div></div></div>`;
  }).join('') || `<div class="empty">${escapeText(t('noData'))}</div>`;
  node.className = 'section bypass-section';
  node.innerHTML = `<h3>${escapeText(t('bypassAnalysis'))}</h3><p>${escapeText(t('bypassSubtitle'))}</p><div class="bypass-summary"><div><small>${escapeText(t('cumulativeTraffic'))}</small><strong>${bytes(bypass.totalBytes)}</strong><span>${new Intl.NumberFormat(language).format(Number(bypass.totalPackets || 0))} ${escapeText(t('units').packets)}</span></div><div><small>${escapeText(t('windowTraffic'))}</small><strong>${bytes(bypass.windowBytes)}</strong><span>${new Intl.NumberFormat(language).format(Number(bypass.windowPackets || 0))} ${escapeText(t('units').packets)}</span></div><div><small>${escapeText(t('capturedKeys'))}</small><strong>${entries.length} / ${new Intl.NumberFormat(language).format(Number(bypass.distinctKeys || 0))}</strong><span>${escapeText(t('distinctKeys'))}</span></div></div><div class="bypass-subheading"><h4>${escapeText(t('bypassSources'))}</h4></div><div class="bypass-list">${sourceRows}</div><div class="pagination" id="bypassSources-pagination"></div><div class="bypass-subheading"><h4>${escapeText(t('bypassEntries'))}</h4></div><div class="bypass-list">${entryRows}</div><div class="pagination" id="bypassEntries-pagination"></div>`;
  renderPagination('bypassSources', sources.length, () => renderBypass(bypass));
  renderPagination('bypassEntries', entries.length, () => renderBypass(bypass));
}

function aggregateBypassSources(entries, bypass) {
  const sources = new Map();
  entries.forEach(entry => {
    const name = bypassSource(entry);
    const source = sources.get(name) || { name, bytes: 0, packets: 0, keys: 0 };
    source.bytes += Number(entry.totalBytes || 0);
    source.packets += Number(entry.totalPackets || 0);
    source.keys++;
    sources.set(name, source);
  });
  const listedBytes = [...sources.values()].reduce((sum, source) => sum + source.bytes, 0);
  const listedPackets = [...sources.values()].reduce((sum, source) => sum + source.packets, 0);
  const missingKeys = Math.max(0, Number(bypass.distinctKeys || 0) - entries.length);
  const missingBytes = Math.max(0, Number(bypass.totalBytes || 0) - listedBytes);
  const missingPackets = Math.max(0, Number(bypass.totalPackets || 0) - listedPackets);
  if (missingKeys || missingBytes || missingPackets) sources.set('__unlisted__', { name: t('unlistedKeys'), bytes: missingBytes, packets: missingPackets, keys: missingKeys });
  return [...sources.values()].sort((a, b) => b.bytes - a.bytes);
}

function bypassSource(entry) {
  const channel = String(entry.payloadChannel || '').trim().toLowerCase();
  if (channel.includes(':')) return channel.slice(0, channel.indexOf(':'));
  if (channel) return channel;
  const packetClass = String(entry.packetClass || '');
  if (packetClass.startsWith('net.minecraft.')) return 'minecraft';
  return 'other';
}

function renderMetricGroups(metrics) {
  const groups = [];
  metrics.forEach(item => {
    let group = groups.find(entry => entry.scope === item.scope);
    if (!group) { group = { scope: item.scope, items: [] }; groups.push(group); }
    group.items.push(item);
  });
  return `<div class="metric-groups">${groups.map(group => {
    const maximumBytes = Math.max(1, ...group.items.filter(item => item.unit === 'bytes').map(item => Number(item.value || 0)));
    const rows = group.items.map(item => renderVisualMetric(item, maximumBytes)).join('');
    return `<div class="metric-group scope-${escapeAttribute(group.scope)}"><div class="metric-group-title">${escapeText(t('scopes')[group.scope] || group.scope)}</div>${rows}</div>`;
  }).join('')}</div>`;
}

function renderVisualMetric(item, maximumBytes) {
  const label = escapeText(metricLabel(item));
  const stage = escapeText(t('stages')[item.stage] || item.stage);
  const value = escapeText(metricValue(item));
  if (item.unit !== 'bytes') return `<div class="visual-metric count-metric"><div class="visual-label"><strong>${label}</strong><small>${stage}</small></div><output>${value}</output></div>`;
  const percentage = Math.max(0, Math.min(100, Number(item.value || 0) * 100 / maximumBytes));
  return `<div class="visual-metric"><div class="visual-label"><strong>${label}</strong><small>${stage}</small></div><meter class="visual-track" min="0" max="100" value="${percentage.toFixed(3)}" aria-label="${escapeAttribute(`${metricLabel(item)} ${metricValue(item)}`)}"></meter><output>${value}</output></div>`;
}

function renderBooleanMetrics(metrics) {
  return `<div class="diagnostic-grid">${metrics.map(item => {
    const enabled = Number(item.value) !== 0;
    return `<div class="diagnostic-item"><strong>${escapeText(metricLabel(item))}</strong><span class="boolean-state" role="switch" aria-readonly="true" aria-checked="${enabled}"><i></i><span>${escapeText(enabled ? t('enabled') : t('disabled'))}</span></span></div>`;
  }).join('')}</div>`;
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
function metricValue(item) { if (item.unit === 'bytes') return bytes(item.value); if (item.unit === 'boolean') return Number(item.value) ? t('enabled') : t('disabled'); return `${new Intl.NumberFormat(language).format(Number(item.value || 0))} ${t('units')[item.unit] || item.unit}`; }
function totalWire(traffic) { return Number(traffic?.outboundWireBytes || 0) + Number(traffic?.inboundWireBytes || 0); }
function emptyRow(columns) { return `<tr><td colspan="${columns}" class="empty">${escapeText(t('noData'))}</td></tr>`; }
function formatDate(value) { return new Intl.DateTimeFormat(language, { dateStyle: 'medium' }).format(new Date(value)); }
function formatDateTime(value) { return new Intl.DateTimeFormat(language, { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value)); }
function formatHour(value) { return new Intl.DateTimeFormat(language, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value)); }
function formatChartHour(value) { return value == null ? '' : new Intl.DateTimeFormat(language, { month: '2-digit', day: '2-digit', hour: '2-digit' }).format(new Date(value)); }
function escapeText(value) { const node = document.createElement('span'); node.textContent = value == null ? '' : String(value); return node.innerHTML; }
function escapeAttribute(value) { return String(value == null ? '' : value).replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll("'", '&#39;').replaceAll('<', '&lt;').replaceAll('>', '&gt;'); }

languageNode.addEventListener('change', () => { language = languageNode.value; localStorage.setItem('bostats.language', language); reportBundle ? render() : uploadViewNode.hidden ? setStatus(statusKey, !retryNode.hidden) : renderUpload(); });
uploadFileNode.addEventListener('change', () => selectUploadFile(uploadFileNode.files?.[0]));
uploadDropNode.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); uploadFileNode.click(); } });
uploadDropNode.addEventListener('dragover', event => { event.preventDefault(); uploadDropNode.classList.add('dragging'); });
uploadDropNode.addEventListener('dragleave', () => uploadDropNode.classList.remove('dragging'));
uploadDropNode.addEventListener('drop', event => { event.preventDefault(); uploadDropNode.classList.remove('dragging'); selectUploadFile(event.dataTransfer?.files?.[0]); });
uploadSubmitNode.addEventListener('click', uploadSelectedReport);
document.querySelectorAll('#view-tabs button').forEach(button => button.addEventListener('click', () => { hideRankingTooltip(); setActiveView(button.dataset.view); }));
document.addEventListener('pointerover', event => {
  const segment = rankingSegment(event.target);
  if (segment) showRankingTooltip(segment, event.clientX, event.clientY);
});
document.addEventListener('pointermove', event => {
  const segment = rankingSegment(event.target);
  if (segment) showRankingTooltip(segment, event.clientX, event.clientY);
});
document.addEventListener('pointerout', event => { if (rankingSegment(event.target)) hideRankingTooltip(); });
document.addEventListener('focusin', event => {
  const segment = rankingSegment(event.target);
  if (!segment) return;
  const bounds = segment.getBoundingClientRect();
  showRankingTooltip(segment, bounds.left + bounds.width / 2, bounds.top);
});
document.addEventListener('focusout', event => { if (rankingSegment(event.target)) hideRankingTooltip(); });
window.addEventListener('scroll', hideRankingTooltip, true);
window.addEventListener('hashchange', () => setActiveView(location.hash.slice(1), false));
document.querySelector('#trend-player').addEventListener('change', () => { chartHoverIndex = -1; renderTrend(reportBundle?.trafficHistory); });
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
