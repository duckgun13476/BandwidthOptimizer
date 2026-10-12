const translations = {
  'zh-CN': {
    language: '语言', loading: '正在读取报告...', invalidLink: '请打开一条完整的报告链接。', retry: '重试',
    missing: '报告不存在或已过期。', failed: '读取报告失败。', timedOut: '读取报告超时。', title: '带宽报告',
    subtitle: '服务器带宽使用与优化结果', trend: '小时流量趋势', dataRange: '数据范围', serverTotal: '全服总量', version: '版本', allVersions: '全部版本', legacyVersion: '< 133版本',
    tabOverview: '总览', tabPlayers: '玩家', tabDetails: '传输详情', tabPackets: '包分析', pageNavigation: '报告页面', detailsSubtitle: '各压缩阶段与运行状态', packetsSubtitle: '旁路来源与包级流量明细', transportFlow: '传输管线', nativeReference: '原版估算参考', packetStructure: '包与映射构成', wireEfficiency: '线路效率', measuredWire: '实际线路构成', avoidedTraffic: '缓存与门控节省', hotspotEfficiency: '热点区块效率', memoryFootprint: '重用模型与实际保留', cacheReuse: '区块缓存复用', diagnosticsEnabled: '项诊断已开启', shadowCoverageNote: '逻辑覆盖规模是重用算法按完整快照计算的假设内存，仅表示可复用数据规模，不是 JVM 实际占用。',
    month: '月度玩家排行', current: '当前小时排行', details: '传输详情', player: '玩家', hour: '小时',
    totalWire: '实际总流量', outboundWire: '出站实际', inboundWire: '入站实际', rawOutbound: '原始出站', boFrame: 'BO 帧',
    bypass: '旁路', status: '状态', complete: '已完成', partial: '进行中', noData: '暂无流量数据', sessionOutbound: '本次出站实际',
    sessionInbound: '本次入站实际', monthTotal: '本月实际总流量', monthOutbound: '本月出站实际', monthInbound: '本月入站实际',
    players: '本月玩家', metric: '指标', value: '值', stage: '阶段', scope: '范围', total: '总流量', outbound: '出站', inbound: '入站',
    records: '{count} 条记录', page: '第 {page} / {pages} 页', rowsPerPage: '每页', previous: '上一页', next: '下一页',
    enabled: '已开启', disabled: '已关闭', chartLabel: '按小时统计的实际线路流量曲线', rankingLabel: '按实际总流量从高到低排列的玩家',
    bypassAnalysis: '旁路流量分析', bypassSubtitle: '自定义通道和包体注册表 ID 按内容命名空间归属；混合包保持多来源，不虚假拆分流量。', bypassSources: '内容来源与协议归属', bypassEntries: '包级明细', capturedKeys: '已上传键', distinctKeys: '不同键', cumulativeTraffic: '累计流量', windowTraffic: '当前窗口', unlistedKeys: '未列出的键', sharedProtocol: '共享原版协议', thirdPartyPacket: '第三方包类', protocolControl: '协议控制', multipleSources: '多个内容来源', contentSource: '内容来源', familyRecipeRegistry: '配方与注册表', familyEntity: '实体同步', familyWorld: '区块与方块', familyEffects: '声音与特效', familyPlayerUi: '玩家与界面', familyConnection: '连接与配置', familyOther: '其他协议',
    uploadTitle: '上传带宽报告', uploadSubtitle: '选择 BandwidthOptimizer 生成的报告文件', uploadPrompt: '将报告拖到这里', uploadChoice: '或选择文件', uploadHint: 'JSON 或 Gzip，最大 4 MiB', noFile: '尚未选择文件', uploadReport: '上传报告', uploading: '正在上传并校验报告…', invalidReport: '这不是可用的 BO 报告文件。', reportTooLarge: '报告解压后不能超过 4 MiB。', uploadFailed: '上传失败，请稍后重试。',
    sections: { transport: '传输管线', server: '服务器会话', entity_interpolation: '实体移动插值', recipe: '配方同步', chunk: '区块传输与缓存', diagnostics: '诊断状态' },
    descriptions: {
      transport: '逻辑包、映射、Zstd、BO 帧与旁路是相互独立的统计阶段。',
      server: '服务器聚合计数，不包含地址或连接标识。',
      entity_interpolation: '本次服务器运行中，相对移动包的合并情况。客户端按原版逻辑插值；包体削减发生在压缩前，不代表实测线路节省。',
      recipe: '持久化配方复用与精确差量传输，统计范围为本次服务器运行。',
      chunk: '区块协议流量、缓存复用、重用模型规模与当前保留载荷。', diagnostics: '仅显示诊断工具开关，不包含诊断日志内容。'
    },
    stages: { logical_packet: '逻辑包', minecraft_compression_estimate: '原版压缩估算', mapping: '映射', zstd_body: 'Zstd 主体', bo_frame: 'BO 帧', recipe_sync: '配方同步', recipe_payload: '配方载荷', socket_wire: '实际线路', derived: '派生值', chunk_reuse: '区块复用', gate_input: '门控输入', gate_suppressed: '门控削减', chunk_frame: '区块帧', memory: '内存', connection: '连接', diagnostic: '诊断' },
    scopes: { outbound: '出站', inbound: '入站', server_session: '服务器会话', session: '会话', current: '当前' },
    units: { frames: '帧', packets: '包', entries: '项', channels: '连接', players: '玩家' }
  },
  'en-US': {
    language: 'Language', loading: 'Loading report...', invalidLink: 'Open a complete report link.', retry: 'Retry',
    missing: 'The report does not exist or has expired.', failed: 'Failed to load the report.', timedOut: 'Report request timed out.', title: 'Bandwidth report',
    subtitle: 'Server bandwidth use and optimization results', trend: 'Hourly traffic trend', dataRange: 'Data range', serverTotal: 'All players', version: 'Version', allVersions: 'All versions', legacyVersion: '< v133',
    tabOverview: 'Overview', tabPlayers: 'Players', tabDetails: 'Transport details', tabPackets: 'Packet analysis', pageNavigation: 'Report pages', detailsSubtitle: 'Compression stages and runtime state', packetsSubtitle: 'Bypass sources and packet-level traffic details', transportFlow: 'Transport pipeline', nativeReference: 'Vanilla estimate reference', packetStructure: 'Packet and mapping composition', wireEfficiency: 'Wire efficiency', measuredWire: 'Measured wire composition', avoidedTraffic: 'Cache and gate savings', hotspotEfficiency: 'Hotspot chunk efficiency', memoryFootprint: 'Reuse model and actual retention', cacheReuse: 'Chunk cache reuse', diagnosticsEnabled: 'diagnostics enabled', shadowCoverageNote: 'Logical coverage is the reuse algorithm\'s assumed memory for complete snapshots. It describes reusable data scale, not actual JVM memory usage.',
    month: 'Monthly player ranking', current: 'Current-hour ranking', details: 'Transport details', player: 'Player', hour: 'Hour',
    totalWire: 'Measured total', outboundWire: 'Outbound measured', inboundWire: 'Inbound measured', rawOutbound: 'Outbound raw', boFrame: 'BO frames',
    bypass: 'Bypass', status: 'Status', complete: 'Complete', partial: 'In progress', noData: 'No traffic data', sessionOutbound: 'Session outbound',
    sessionInbound: 'Session inbound', monthTotal: 'Monthly measured total', monthOutbound: 'Monthly outbound', monthInbound: 'Monthly inbound',
    players: 'Monthly players', metric: 'Metric', value: 'Value', stage: 'Stage', scope: 'Scope', total: 'Total', outbound: 'Outbound', inbound: 'Inbound',
    records: '{count} records', page: 'Page {page} of {pages}', rowsPerPage: 'Rows', previous: 'Previous page', next: 'Next page',
    enabled: 'Enabled', disabled: 'Disabled', chartLabel: 'Measured wire traffic by hour', rankingLabel: 'Players ranked by measured total traffic',
    bypassAnalysis: 'Bypass traffic analysis', bypassSubtitle: 'Custom channels and packet registry IDs are attributed by content namespace. Mixed packets remain multi-source instead of receiving invented byte splits.', bypassSources: 'Content and protocol attribution', bypassEntries: 'Packet details', capturedKeys: 'Uploaded keys', distinctKeys: 'Distinct keys', cumulativeTraffic: 'Cumulative traffic', windowTraffic: 'Current window', unlistedKeys: 'Unlisted keys', sharedProtocol: 'Shared vanilla protocol', thirdPartyPacket: 'Third-party packet class', protocolControl: 'Protocol control', multipleSources: 'Multiple content sources', contentSource: 'Content source', familyRecipeRegistry: 'Recipes and registries', familyEntity: 'Entity synchronization', familyWorld: 'Chunks and blocks', familyEffects: 'Sounds and effects', familyPlayerUi: 'Player and UI', familyConnection: 'Connection and configuration', familyOther: 'Other protocol',
    uploadTitle: 'Upload bandwidth report', uploadSubtitle: 'Select a report generated by BandwidthOptimizer', uploadPrompt: 'Drop a report here', uploadChoice: 'or choose a file', uploadHint: 'JSON or Gzip, up to 4 MiB', noFile: 'No file selected', uploadReport: 'Upload report', uploading: 'Uploading and validating report…', invalidReport: 'This is not a valid BO report file.', reportTooLarge: 'The decompressed report must not exceed 4 MiB.', uploadFailed: 'Upload failed. Try again later.',
    sections: { transport: 'Transport pipeline', server: 'Server session', entity_interpolation: 'Entity movement interpolation', recipe: 'Recipe synchronization', chunk: 'Chunk transport and cache', diagnostics: 'Diagnostic state' },
    descriptions: {
      transport: 'Logical packets, mapping, Zstd, BO frames, and bypass are separate accounting stages.',
      server: 'Aggregate server counters without addresses or connection identifiers.',
      entity_interpolation: 'Relative movement pairing during this server run. Vanilla clients interpolate delivered endpoints; payload reduction is before compression, not measured wire savings.',
      recipe: 'Persistent recipe reuse and exact delta traffic for this server run.',
      chunk: 'Chunk protocol traffic, cache reuse, reuse-model scale, and currently retained payload.', diagnostics: 'Diagnostic tool state only; log contents are not embedded.'
    },
    stages: { logical_packet: 'Logical packet', minecraft_compression_estimate: 'Vanilla estimate', mapping: 'Mapping', zstd_body: 'Zstd body', bo_frame: 'BO frame', recipe_sync: 'Recipe sync', recipe_payload: 'Recipe payload', socket_wire: 'Measured wire', derived: 'Derived', chunk_reuse: 'Chunk reuse', gate_input: 'Gate input', gate_suppressed: 'Gate suppressed', chunk_frame: 'Chunk frame', memory: 'Memory', connection: 'Connection', diagnostic: 'Diagnostic' },
    scopes: { outbound: 'Outbound', inbound: 'Inbound', server_session: 'Server session', session: 'Session', current: 'Current' },
    units: { frames: 'frames', packets: 'packets', entries: 'entries', channels: 'channels', players: 'players' }
  },
  'pt-BR': {
    language: 'Idioma', loading: 'Carregando relatório...', invalidLink: 'Abra um link completo de relatório.', retry: 'Tentar novamente',
    missing: 'O relatório não existe ou expirou.', failed: 'Falha ao carregar o relatório.', timedOut: 'A leitura do relatório expirou.', title: 'Relatório de largura de banda',
    subtitle: 'Uso de largura de banda e resultados da otimização', trend: 'Tendência de tráfego por hora', dataRange: 'Intervalo de dados', serverTotal: 'Todos os jogadores', version: 'Versão', allVersions: 'Todas as versões', legacyVersion: '< versão 133',
    tabOverview: 'Visão geral', tabPlayers: 'Jogadores', tabDetails: 'Detalhes do transporte', tabPackets: 'Análise de pacotes', pageNavigation: 'Páginas do relatório', detailsSubtitle: 'Etapas de compressão e estado de execução', packetsSubtitle: 'Origens de desvio e detalhes de tráfego por pacote', transportFlow: 'Pipeline de transporte', nativeReference: 'Referência da estimativa vanilla', packetStructure: 'Composição de pacotes e mapeamento', wireEfficiency: 'Eficiência da rede', measuredWire: 'Composição do tráfego medido', avoidedTraffic: 'Economia de cache e gates', hotspotEfficiency: 'Eficiência dos chunks hotspot', memoryFootprint: 'Modelo de reuso e retenção real', cacheReuse: 'Reuso do cache de chunks', diagnosticsEnabled: 'diagnósticos ativos', shadowCoverageNote: 'A cobertura lógica é a memória presumida pelo algoritmo de reuso para snapshots completos. Ela representa a escala reutilizável, não o uso real de memória da JVM.',
    month: 'Ranking mensal de jogadores', current: 'Ranking da hora atual', details: 'Detalhes do transporte', player: 'Jogador', hour: 'Hora',
    totalWire: 'Total medido', outboundWire: 'Saída medida', inboundWire: 'Entrada medida', rawOutbound: 'Saída original', boFrame: 'Quadros BO',
    bypass: 'Desvio', status: 'Estado', complete: 'Concluído', partial: 'Em andamento', noData: 'Sem dados de tráfego', sessionOutbound: 'Saída da sessão',
    sessionInbound: 'Entrada da sessão', monthTotal: 'Total mensal medido', monthOutbound: 'Saída mensal', monthInbound: 'Entrada mensal',
    players: 'Jogadores no mês', metric: 'Métrica', value: 'Valor', stage: 'Etapa', scope: 'Escopo', total: 'Total', outbound: 'Saída', inbound: 'Entrada',
    records: '{count} registros', page: 'Página {page} de {pages}', rowsPerPage: 'Linhas', previous: 'Página anterior', next: 'Próxima página',
    enabled: 'Ativado', disabled: 'Desativado', chartLabel: 'Tráfego medido por hora', rankingLabel: 'Jogadores classificados pelo tráfego total medido',
    bypassAnalysis: 'Análise do tráfego desviado', bypassSubtitle: 'Canais personalizados e IDs de registro dos pacotes são atribuídos pelo namespace do conteúdo. Pacotes mistos permanecem com múltiplas fontes.', bypassSources: 'Atribuição de conteúdo e protocolo', bypassEntries: 'Detalhes dos pacotes', capturedKeys: 'Chaves enviadas', distinctKeys: 'Chaves distintas', cumulativeTraffic: 'Tráfego acumulado', windowTraffic: 'Janela atual', unlistedKeys: 'Chaves não listadas', sharedProtocol: 'Protocolo vanilla compartilhado', thirdPartyPacket: 'Classe de pacote de terceiros', protocolControl: 'Controle de protocolo', multipleSources: 'Múltiplas fontes de conteúdo', contentSource: 'Fonte do conteúdo', familyRecipeRegistry: 'Receitas e registros', familyEntity: 'Sincronização de entidades', familyWorld: 'Chunks e blocos', familyEffects: 'Sons e efeitos', familyPlayerUi: 'Jogador e interface', familyConnection: 'Conexão e configuração', familyOther: 'Outro protocolo',
    uploadTitle: 'Enviar relatório de largura de banda', uploadSubtitle: 'Selecione um relatório gerado pelo BandwidthOptimizer', uploadPrompt: 'Solte um relatório aqui', uploadChoice: 'ou escolha um arquivo', uploadHint: 'JSON ou Gzip, até 4 MiB', noFile: 'Nenhum arquivo selecionado', uploadReport: 'Enviar relatório', uploading: 'Enviando e validando o relatório…', invalidReport: 'Este não é um relatório BO válido.', reportTooLarge: 'O relatório descompactado não pode exceder 4 MiB.', uploadFailed: 'Falha no envio. Tente novamente mais tarde.',
    sections: { transport: 'Pipeline de transporte', server: 'Sessão do servidor', entity_interpolation: 'Interpolação do movimento de entidades', recipe: 'Sincronização de receitas', chunk: 'Transporte e cache de chunks', diagnostics: 'Estado do diagnóstico' },
    descriptions: {
      transport: 'Pacotes lógicos, mapeamento, Zstd, quadros BO e desvio são etapas contábeis separadas.',
      server: 'Contadores agregados sem endereços ou identificadores de conexão.',
      entity_interpolation: 'Combinação de movimentos relativos nesta execução do servidor. O cliente vanilla interpola os pontos recebidos; a redução do payload ocorre antes da compressão e não mede economia na rede.',
      recipe: 'Reuso persistente de receitas e tráfego de deltas exatos nesta execução do servidor.',
      chunk: 'Tráfego de chunks, reuso de cache, escala do modelo de reuso e payload retido atualmente.', diagnostics: 'Apenas o estado das ferramentas; o conteúdo dos logs não é incluído.'
    },
    stages: { logical_packet: 'Pacote lógico', minecraft_compression_estimate: 'Estimativa vanilla', mapping: 'Mapeamento', zstd_body: 'Corpo Zstd', bo_frame: 'Quadro BO', recipe_sync: 'Sincronização de receitas', recipe_payload: 'Payload de receitas', socket_wire: 'Tráfego medido', derived: 'Derivado', chunk_reuse: 'Reuso de chunks', gate_input: 'Entrada do gate', gate_suppressed: 'Suprimido pelo gate', chunk_frame: 'Quadro de chunk', memory: 'Memória', connection: 'Conexão', diagnostic: 'Diagnóstico' },
    scopes: { outbound: 'Saída', inbound: 'Entrada', server_session: 'Sessão do servidor', session: 'Sessão', current: 'Atual' },
    units: { frames: 'quadros', packets: 'pacotes', entries: 'entradas', channels: 'conexões', players: 'jogadores' }
  }
};

const metricLabels = {
  'zh-CN': { frames: '帧', packets: '逻辑包', baseline_bytes: '逻辑字节', vanilla_estimate_bytes: '原版压缩估算', mapping_bytes: '映射字节', zstd_body_bytes: 'Zstd 主体字节', bo_frame_bytes: 'BO 帧字节', bypass_packets: '旁路包', bypass_bytes: '旁路字节', literal_entries: '原文项', exact_refs: '精确引用', template_refs: '模板引用', active_channels: '活跃连接', bound_players: '已绑定玩家', outbound_raw_bytes: '出站原始字节', outbound_vanilla_estimate_bytes: '出站原版估算', outbound_wire_bytes: '出站实际线路', inbound_wire_bytes: '入站实际线路', outbound_saved_bytes: '出站节省', offline_reuse_saved_bytes: '离线缓存节省', temporary_reuse_saved_bytes: '临时缓存节省', create_gate_observed_bytes: 'Create 门控观察量', create_gate_saved_bytes: 'Create 门控节省', idle_gate_saved_bytes: '挂机门控节省', 'hotspot.outbound.logical_bytes': '热点出站逻辑字节', 'hotspot.outbound.frame_bytes': '热点出站帧字节', 'hotspot.inbound.logical_bytes': '热点入站逻辑字节', 'shadow.total_bytes': '快照逻辑覆盖规模（非内存）', 'shadow.retained_original_bytes': '当前保留的原始载荷', 'runtime.total_bytes': '运行时引用载荷', 'reuse.temporary_saved_bytes': '客户端临时复用节省', 'reuse.offline_saved_bytes': '客户端离线复用节省' },
  'en-US': { 'shadow.total_bytes': 'Logical snapshot coverage (not memory)', 'shadow.retained_original_bytes': 'Currently retained original payload', 'runtime.total_bytes': 'Runtime reference payload' },
  'pt-BR': { frames: 'Quadros', packets: 'Pacotes lógicos', baseline_bytes: 'Bytes lógicos', vanilla_estimate_bytes: 'Estimativa vanilla', mapping_bytes: 'Bytes de mapeamento', zstd_body_bytes: 'Bytes do corpo Zstd', bo_frame_bytes: 'Bytes dos quadros BO', bypass_packets: 'Pacotes desviados', bypass_bytes: 'Bytes desviados', literal_entries: 'Entradas literais', exact_refs: 'Referências exatas', template_refs: 'Referências de modelo', active_channels: 'Conexões ativas', bound_players: 'Jogadores vinculados', outbound_raw_bytes: 'Saída original', outbound_vanilla_estimate_bytes: 'Estimativa vanilla de saída', outbound_wire_bytes: 'Saída medida', inbound_wire_bytes: 'Entrada medida', outbound_saved_bytes: 'Saída economizada', offline_reuse_saved_bytes: 'Economia do cache offline', temporary_reuse_saved_bytes: 'Economia do cache temporário', create_gate_observed_bytes: 'Observado pelo gate Create', create_gate_saved_bytes: 'Economia do gate Create', idle_gate_saved_bytes: 'Economia do gate AFK', 'hotspot.outbound.logical_bytes': 'Saída lógica hotspot', 'hotspot.outbound.frame_bytes': 'Quadros de saída hotspot', 'hotspot.inbound.logical_bytes': 'Entrada lógica hotspot', 'shadow.total_bytes': 'Cobertura lógica dos snapshots (não é memória)', 'shadow.retained_original_bytes': 'Payload original retido atualmente', 'runtime.total_bytes': 'Payload de referência em execução', 'reuse.temporary_saved_bytes': 'Reuso temporário do cliente', 'reuse.offline_saved_bytes': 'Reuso offline do cliente' }
};

const recipeLabels = {
  'zh-CN': { frames: '配方同步帧', full_frames: '完整帧', identity_frames: '复用帧', structural_delta_frames: '结构差量帧', byte_delta_frames: '字节差量帧', logical_bytes: '配方原始字节', payload_bytes: '配方载荷字节', frame_bytes: '配方 BO 帧字节', saved_bytes: '配方节省字节' },
  'en-US': { frames: 'Recipe sync frames', full_frames: 'Full frames', identity_frames: 'Reuse frames', structural_delta_frames: 'Structural delta frames', byte_delta_frames: 'Byte delta frames', logical_bytes: 'Recipe logical bytes', payload_bytes: 'Recipe payload bytes', frame_bytes: 'Recipe BO frame bytes', saved_bytes: 'Recipe saved bytes' },
  'pt-BR': { frames: 'Quadros de sincronização de receitas', full_frames: 'Quadros completos', identity_frames: 'Quadros de reuso', structural_delta_frames: 'Quadros de delta estrutural', byte_delta_frames: 'Quadros de delta de bytes', logical_bytes: 'Bytes lógicos de receitas', payload_bytes: 'Bytes do payload de receitas', frame_bytes: 'Bytes dos quadros BO de receitas', saved_bytes: 'Bytes economizados em receitas' }
};

const entityInterpolationLabels = {
  'zh-CN': { enabled: '实体移动合并已启用', observed_packets: '观察到的相对移动包', observed_payload_bytes: '合并前移动包体', captured_packets: '临时暂存的移动包', coalesced_packets: '成功合并的包对', saved_payload_bytes: '合并减少的包体字节', timeout_releases: '未配对按时放行', overflow_releases: '位移溢出放行', superseded_packets: '被传送或移除覆盖', pending_entities: '当前暂存移动包' },
  'en-US': { enabled: 'Entity movement pairing enabled', observed_packets: 'Observed relative movement packets', observed_payload_bytes: 'Movement payload before pairing', captured_packets: 'Movement packets temporarily held', coalesced_packets: 'Coalesced packet pairs', saved_payload_bytes: 'Payload bytes removed by pairing', timeout_releases: 'Unpaired packets released', overflow_releases: 'Delta overflow releases', superseded_packets: 'Superseded by teleport or removal', pending_entities: 'Movement packets currently held' },
  'pt-BR': { enabled: 'Combinação de movimentos ativada', observed_packets: 'Pacotes de movimento relativo observados', observed_payload_bytes: 'Payload de movimento antes da combinação', captured_packets: 'Pacotes de movimento retidos temporariamente', coalesced_packets: 'Pares de pacotes combinados', saved_payload_bytes: 'Bytes de payload reduzidos', timeout_releases: 'Pacotes liberados sem par', overflow_releases: 'Liberações por excesso de delta', superseded_packets: 'Substituídos por teleporte ou remoção', pending_entities: 'Pacotes de movimento retidos agora' }
};

const reportLabels = {
  'zh-CN': { versionScope: '版本（总览、趋势与月排行）', scopeAll: '月数据包含所有版本；当前小时排行始终显示最新快照。', scopeSelected: '月数据仅包含所选版本；当前小时排行始终显示最新快照。', peakHour: '最高小时下行', peakPlayers: '该小时不同玩家', partialHours: '未完成小时', sourceTitle: '已观测来源逻辑流量', sourceNote: '按来源累计的原始逻辑字节，可能包含 BO 载体；不能当作线路流量或逐来源节省量。', sourceMissing: '这份旧报告未包含来源排行。', sourceKeys: '已观测来源键', sourceRaw: '来源逻辑字节', heap_used_bytes: 'JVM 堆已用', heap_committed_bytes: 'JVM 堆已提交', heap_max_bytes: 'JVM 堆上限', uptime_millis: 'JVM 运行时间', gc_count: 'GC 次数', gc_millis: '累计 GC 时间', uploadHint: 'JSON 或 Gzip，解压后最多 16 MiB', reportTooLarge: '报告解压后不能超过 16 MiB。', browserCompression: '此浏览器不能压缩大型报告，请在游戏内上传。', compressedTooLarge: '压缩后仍超过网页请求限制，请在游戏内上传。' },
  'en-US': { versionScope: 'Version (summary, trend, monthly ranking)', scopeAll: 'Monthly data includes all versions; the current-hour ranking always shows the latest snapshot.', scopeSelected: 'Monthly data includes only the selected version; the current-hour ranking always shows the latest snapshot.', peakHour: 'Peak hourly outbound', peakPlayers: 'Distinct players that hour', partialHours: 'Incomplete hours', sourceTitle: 'Observed source logical traffic', sourceNote: 'Cumulative raw logical bytes by source, possibly including BO carriers. These are not socket bytes or per-source savings.', sourceMissing: 'This older report has no source ranking.', sourceKeys: 'Observed source keys', sourceRaw: 'Source logical bytes', heap_used_bytes: 'JVM heap used', heap_committed_bytes: 'JVM heap committed', heap_max_bytes: 'JVM heap maximum', uptime_millis: 'JVM uptime', gc_count: 'GC collections', gc_millis: 'Cumulative GC time', uploadHint: 'JSON or Gzip, up to 16 MiB uncompressed', reportTooLarge: 'The uncompressed report must not exceed 16 MiB.', browserCompression: 'This browser cannot compress a large report. Upload it in game.', compressedTooLarge: 'The compressed report still exceeds the web request limit. Upload it in game.' },
  'pt-BR': { versionScope: 'Versão (resumo, tendência e ranking mensal)', scopeAll: 'Os dados mensais incluem todas as versões; o ranking da hora atual mostra o registro mais recente.', scopeSelected: 'Os dados mensais incluem apenas a versão selecionada; o ranking da hora atual mostra o registro mais recente.', peakHour: 'Pico de saída por hora', peakPlayers: 'Jogadores distintos nessa hora', partialHours: 'Horas incompletas', sourceTitle: 'Tráfego lógico por origem', sourceNote: 'Bytes lógicos brutos acumulados por origem, possivelmente incluindo quadros BO. Não são bytes de rede nem economia por origem.', sourceMissing: 'Este relatório antigo não contém ranking por origem.', sourceKeys: 'Origens observadas', sourceRaw: 'Bytes lógicos por origem', heap_used_bytes: 'Heap JVM usado', heap_committed_bytes: 'Heap JVM reservado', heap_max_bytes: 'Heap JVM máximo', uptime_millis: 'Tempo de execução JVM', gc_count: 'Coletas GC', gc_millis: 'Tempo GC acumulado', uploadHint: 'JSON ou Gzip, até 16 MiB descompactado', reportTooLarge: 'O relatório descompactado não pode exceder 16 MiB.', browserCompression: 'Este navegador não consegue comprimir um relatório grande. Envie pelo jogo.', compressedTooLarge: 'O relatório comprimido excede o limite da solicitação web. Envie pelo jogo.' }
};
const rt = key => reportLabels[language]?.[key] || reportLabels['en-US'][key] || key;
const sourcePeriodLabel = {
  'zh-CN': '统计窗口：本次服务器运行期间，与上方月度流量不是同一时间范围。',
  'en-US': 'Window: this server run, which may differ from the monthly traffic window above.',
  'pt-BR': 'Período: esta execução do servidor, que pode diferir do tráfego mensal acima.'
};
const periodLabels = {
  'zh-CN': { hour: '小时', day: '天', trend: '流量趋势', granularity: '趋势粒度', dailyNote: '按浏览器时区汇总已记录的小时；缺失小时不补零。', hours: '已记录小时', partial: '未完成记录', details: '累计流量与报告生成时的状态；不受总览的版本筛选影响。', transport: '传输计数器初始化或最近清零后累计。', server: '服务器统计会话累计，包含已断开的连接；手动清零后重新累计。', other: '累计计数从各模块初始化或最近清零起算；内存、缓存和开关为报告生成时的状态。', start: '统计开始', unknown: '旧报告未记录起点', end: '报告生成', counters: '包数量与映射明细' },
  'en-US': { hour: 'Hour', day: 'Day', trend: 'Traffic trend', granularity: 'Trend interval', dailyNote: 'Recorded hours summed in your browser time zone; missing hours are not filled with zero.', hours: 'Recorded hours', partial: 'Incomplete records', details: 'Cumulative traffic and state at report creation; unaffected by the overview version filter.', transport: 'Transport counters since initialization or the last reset.', server: 'Totals for this server statistics session, including closed connections; manual reset starts them again.', other: 'Counters since module initialization or last reset; memory, caches and switches show state at report creation.', start: 'Statistics started', unknown: 'Start not recorded in older report', end: 'Report created', counters: 'Packet counts and mapping details' },
  'pt-BR': { hour: 'Hora', day: 'Dia', trend: 'Tendência do tráfego', granularity: 'Intervalo', dailyNote: 'Horas registradas somadas no fuso do navegador; horas ausentes não são preenchidas com zero.', hours: 'Horas registradas', partial: 'Registros incompletos', details: 'Tráfego acumulado e estado na criação do relatório; não afetados pelo filtro de versão.', transport: 'Contadores de transporte desde a inicialização ou último reset.', server: 'Totais desta sessão, incluindo conexões encerradas; zerar manualmente reinicia os totais.', other: 'Contadores desde a inicialização ou último reset; memória, caches e opções mostram o estado na criação do relatório.', start: 'Início das estatísticas', unknown: 'Início não registrado no relatório antigo', end: 'Relatório criado', counters: 'Contagens e detalhes de mapeamento' }
};
const pt = key => (periodLabels[language] || periodLabels['en-US'])[key];
const cutoffLabels = { 'zh-CN': '统计截至', 'en-US': 'Counters observed at', 'pt-BR': 'Contadores observados em' };
let trendGranularity = 'hour';
const jvmLabels = {
  'zh-CN': { title: 'JVM 运行状态', description: '生成报告时的堆状态及进程累计计数；包含所有模组。', process: '进程累计', jvm_heap: 'JVM 堆', jvm_gc: '垃圾回收', jvm: 'JVM', milliseconds: '毫秒', events: '次' },
  'en-US': { title: 'JVM health', description: 'Heap state at report time and cumulative process counters across all mods.', process: 'Process cumulative', jvm_heap: 'JVM heap', jvm_gc: 'Garbage collection', jvm: 'JVM', milliseconds: 'ms', events: 'events' },
  'pt-BR': { title: 'Estado da JVM', description: 'Estado do heap no momento do relatório e contadores acumulados de todos os mods.', process: 'Processo acumulado', jvm_heap: 'Heap JVM', jvm_gc: 'Coleta de lixo', jvm: 'JVM', milliseconds: 'ms', events: 'eventos' }
};

const languageNode = document.querySelector('#language');
const supportedLanguage = value => value === 'pt-BR' ? value : value?.startsWith('zh') ? 'zh-CN' : 'en-US';
let language = localStorage.getItem('bostats.language') || supportedLanguage(navigator.language);
if (!translations[language]) language = 'en-US';
let reportBundle = null;
const visibleSeries = new Set(['total', 'outbound', 'inbound']);
const pages = { bypassSources: { page: 1, size: 10 }, bypassEntries: { page: 1, size: 10 }, sources: { page: 1, size: 10 } };
let activeView = ['details', 'packets'].includes(location.hash.slice(1)) ? location.hash.slice(1) : 'overview';
if (location.hash === '#players') history.replaceState(null, '', '#overview');
let chartHoverIndex = -1;
const LEGACY_VERSION = '__legacy_pre_133';
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
const hourVersion = hour => typeof hour?.modVersion === 'string' && hour.modVersion.trim() ? hour.modVersion.trim() : LEGACY_VERSION;
const versionLabel = value => value === LEGACY_VERSION || !value ? t('legacyVersion') : value.replaceAll('_', '.');

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
  document.querySelector('#report-version').textContent = '';
  document.querySelector('#report-id').textContent = '';
  document.querySelector('#status').hidden = true;
  document.querySelectorAll('.view-page').forEach(node => { node.hidden = true; });
  uploadViewNode.hidden = false;
  document.querySelector('#upload-title').textContent = t('uploadTitle');
  document.querySelector('#upload-subtitle').textContent = t('uploadSubtitle');
  document.querySelector('#upload-prompt').textContent = t('uploadPrompt');
  document.querySelector('#upload-choice').textContent = t('uploadChoice');
  document.querySelector('#upload-hint').textContent = rt('uploadHint');
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
  const limit = 16 * 1024 * 1024;
  if (file.size > limit) throw new Error('reportTooLarge');
  let data = await file.arrayBuffer();
  const bytesView = new Uint8Array(data);
  if (bytesView[0] === 0x1f && bytesView[1] === 0x8b) {
    if (typeof DecompressionStream !== 'function') throw new Error('invalidReport');
    const reader = new Blob([data]).stream().pipeThrough(new DecompressionStream('gzip')).getReader();
    const chunks = [];
    let total = 0;
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        total += value.byteLength;
        if (total > limit) throw new Error('reportTooLarge');
        chunks.push(value);
      }
    } catch (error) {
      await reader.cancel().catch(() => {});
      throw error?.message === 'reportTooLarge' ? error : new Error('invalidReport');
    } finally {
      reader.releaseLock();
    }
    const decoded = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) { decoded.set(chunk, offset); offset += chunk.byteLength; }
    data = decoded.buffer;
  }
  if (data.byteLength > limit) throw new Error('reportTooLarge');
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
    let report;
    try { report = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(data)); }
    catch { throw new Error('invalidReport'); }
    if (report?.schemaVersion !== 1 || typeof report.reportId !== 'string' || !report.summary || !report.playerTraffic) throw new Error('invalidReport');
    const headers = { 'Content-Type': 'application/vnd.bandwidthoptimizer.report-bundle+json' };
    let body = data;
    if (data.byteLength > 4 * 1024 * 1024) {
      if (typeof CompressionStream !== 'function') throw new Error('browserCompression');
      body = await new Response(new Blob([data]).stream().pipeThrough(new CompressionStream('gzip'))).arrayBuffer();
      if (body.byteLength > 4 * 1024 * 1024) throw new Error('compressedTooLarge');
      headers['Content-Encoding'] = 'gzip';
    }
    const response = await fetch('/api/v1/reports', { method: 'POST', headers, body });
    const result = await response.json().catch(() => ({}));
    if (!response.ok || !result.url) throw new Error(result.error || 'uploadFailed');
    location.assign(result.url);
  } catch (error) {
    const key = ['invalidReport', 'reportTooLarge', 'browserCompression', 'compressedTooLarge'].includes(error?.message) ? error.message : 'uploadFailed';
    uploadStatusNode.textContent = rt(key) === key ? t(key) : rt(key);
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
  document.querySelector('#trend-version-label').textContent = t('version');
  document.querySelector('#trend-version-label').textContent = rt('versionScope');
  document.querySelector('#trend-player-label').textContent = t('dataRange');
  document.querySelector('#month-title').textContent = t('month');
  document.querySelector('#current-title').textContent = t('current');
  document.querySelector('#details-title').textContent = t('details');
  document.querySelector('#details-subtitle').textContent = t('detailsSubtitle');
  document.querySelector('#packets-title').textContent = t('tabPackets');
  document.querySelector('#packets-subtitle').textContent = t('packetsSubtitle');
  document.querySelectorAll('[data-i18n]').forEach(node => { node.textContent = t(node.dataset.i18n); });
  document.querySelector('#report-id').textContent = bundle.reportId;
  document.querySelector('#report-version').textContent = versionLabel(summary?.modVersion || bundle.modVersion);
  document.querySelector('#generated').textContent = formatDateTime(bundle.generatedAtMillis);

  const server = summary.sections.find(section => section.id === 'server');
  const values = Object.fromEntries((server?.metrics || []).map(item => [item.id, item.value]));
  populateVersionSelector(history);
  const selectedHistory = selectVersionHistory(history);
  const monthTotals = selectedHistory.totals || traffic?.totals || {};
  const monthPlayers = selectedHistory.players || traffic?.players || [];
  const peak = selectedHistory.hours.reduce((best, hour) =>
    !best || Number(hour.totals?.outboundWireBytes || 0) > Number(best.totals?.outboundWireBytes || 0) ? hour : best, null);
  document.querySelector('#summary').innerHTML = [
    metric(t('sessionOutbound'), bytes(values.outbound_wire_bytes)), metric(t('sessionInbound'), bytes(values.inbound_wire_bytes)),
    metric(t('monthTotal'), bytes(totalWire(monthTotals))), metric(t('monthOutbound'), bytes(monthTotals.outboundWireBytes)),
    metric(t('monthInbound'), bytes(monthTotals.inboundWireBytes)), metric(t('players'), `${monthPlayers.length}`),
    metric(rt('peakHour'), peak ? `${bytes(peak.totals?.outboundWireBytes)} · ${formatHour(peak.periodStartMillis)}` : t('noData')),
    metric(rt('peakPlayers'), peak ? `${(peak.players || []).length}` : t('noData')),
    metric(rt('partialHours'), `${selectedHistory.hours.filter(hour => !hour.complete).length}`)
  ].join('');
  document.querySelector('#scope-note').textContent = !history ? '' : document.querySelector('#trend-version').value === '__all' ? rt('scopeAll') : rt('scopeSelected');

  populatePlayerSelectors(monthPlayers);
  renderTrend(history);
  renderMonth(monthPlayers, selectedHistory);
  renderCurrent(traffic?.players || [], traffic);
  renderDetails(summary.sections || []);
  renderBypass(summary.bypass);
  renderSourceTraffic(summary.sourceTraffic);

  statusNode.hidden = true;
  document.querySelector('#trend-section').hidden = !history;
  setActiveView(activeView, false);
}

function selectVersionHistory(history) {
  if (!history) return { totals: null, players: null, hours: [] };
  const version = document.querySelector('#trend-version').value;
  if (version === '__all') return history;
  const hours = (history.hours || []).filter(hour => hourVersion(hour) === version);
  const totals = { outboundWireBytes: 0, inboundWireBytes: 0 };
  const players = new Map();
  for (const hour of hours) {
    totals.outboundWireBytes += Number(hour.totals?.outboundWireBytes || 0);
    totals.inboundWireBytes += Number(hour.totals?.inboundWireBytes || 0);
    for (const player of hour.players || []) {
      if (!player.playerUuid) continue;
      let entry = players.get(player.playerUuid);
      if (!entry) { entry = { playerUuid: player.playerUuid, playerName: player.playerName, traffic: { outboundWireBytes: 0, inboundWireBytes: 0 } }; players.set(player.playerUuid, entry); }
      if (player.playerName) entry.playerName = player.playerName;
      entry.traffic.outboundWireBytes += Number(player.outboundWireBytes || 0);
      entry.traffic.inboundWireBytes += Number(player.inboundWireBytes || 0);
    }
  }
  return { ...history, totals, players: [...players.values()], hours };
}

function populateVersionSelector(history) {
  const select = document.querySelector('#trend-version');
  const previous = select.value || '__all';
  const versions = [...new Set((history?.hours || []).map(hourVersion))];
  select.innerHTML = `<option value="__all">${escapeText(t('allVersions'))}</option>${versions.map(version => `<option value="${escapeAttribute(version)}">${escapeText(versionLabel(version))}</option>`).join('')}`;
  select.value = previous === '__all' || versions.includes(previous) ? previous : '__all';
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
  document.querySelector('#trend-title').textContent = pt('trend');
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  document.querySelector('#trend-period').textContent = `${formatDate(history.periodStartMillis)} - ${formatDate(history.periodEndMillis - 1)} · ${zone}${trendGranularity === 'day' ? ' · ' + pt('dailyNote') : ''}`;
  const granularity = document.querySelector('#trend-granularity');
  granularity.setAttribute('aria-label', pt('granularity'));
  granularity.innerHTML = ['hour', 'day'].map(value => `<button type="button" data-interval="${value}" aria-pressed="${trendGranularity === value}">${escapeText(pt(value))}</button>`).join('');
  granularity.querySelectorAll('button').forEach(button => button.addEventListener('click', () => { trendGranularity = button.dataset.interval; chartHoverIndex = -1; renderTrend(history); }));
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
  const selectedVersion = document.querySelector('#trend-version').value;
  const hourly = (history?.hours || []).filter(hour => selectedVersion === '__all' || hourVersion(hour) === selectedVersion).map(hour => {
    const traffic = uuid === '__server' ? hour.totals : (hour.players || []).find(player => player.playerUuid === uuid);
    const outbound = Number(traffic?.outboundWireBytes || 0);
    const inbound = Number(traffic?.inboundWireBytes || 0);
    return { time: hour.periodStartMillis, version: hourVersion(hour), outbound, inbound, total: outbound + inbound, incomplete: !hour.complete };
  }).sort((a, b) => a.time - b.time);
  return trendGranularity === 'day' ? aggregateDailyPoints(hourly) : hourly;
}

function aggregateDailyPoints(hourly) {
  const days = new Map();
  hourly.forEach(point => {
    const date = new Date(point.time);
    const time = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
    let day = days.get(time);
    if (!day) { day = { time, outbound: 0, inbound: 0, total: 0, versions: new Set(), hours: new Set(), partial: 0 }; days.set(time, day); }
    day.outbound += point.outbound; day.inbound += point.inbound; day.total += point.total;
    day.versions.add(point.version); day.hours.add(point.time);
    if (point.incomplete) day.partial++;
  });
  return [...days.values()].sort((a, b) => a.time - b.time).map(day => ({ time: day.time, outbound: day.outbound, inbound: day.inbound, total: day.total, version: [...day.versions].join(' / '), recordedHours: day.hours.size, partial: day.partial }));
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
  if (document.querySelector('#trend-version').value === '__all') {
    let previousLabelX = Number.NEGATIVE_INFINITY;
    let labelRow = 0;
    points.forEach((point, index) => {
      if (index === 0 || point.version === points[index - 1].version) return;
      const transitionX = (x(index - 1) + x(index)) / 2;
      labelRow = transitionX - previousLabelX < 92 ? (labelRow + 1) % 3 : 0;
      previousLabelX = transitionX;
      context.save();
      context.setLineDash([4, 4]);
      context.strokeStyle = text;
      context.lineWidth = 1;
      context.beginPath(); context.moveTo(transitionX, pad.top); context.lineTo(transitionX, pad.top + innerHeight); context.stroke();
      context.restore();
      context.fillStyle = text;
      context.textAlign = 'left';
      context.fillText(versionLabel(point.version), Math.min(transitionX + 5, width - pad.right - 72), pad.top + 8 + labelRow * 14);
    });
  }
  const tickCount = Math.min(5, points.length);
  for (let i = 0; i < tickCount; i++) {
    const index = Math.round((points.length - 1) * i / Math.max(1, tickCount - 1));
    context.fillStyle = text; context.textAlign = i === 0 ? 'left' : i === tickCount - 1 ? 'right' : 'center';
    context.fillText(trendGranularity === 'day' ? formatDate(points[index]?.time) : formatChartHour(points[index]?.time), x(index), height - 14);
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
    const lines = [trendGranularity === 'day' ? formatDate(point.time) : formatHour(point.time), `${t('version')}  ${versionLabel(point.version)}`, ...active.map(series => `${t(series)}  ${bytes(point[series])}`), ...(trendGranularity === 'day' ? [`${pt('hours')}: ${point.recordedHours} · ${pt('partial')}: ${point.partial}`] : [])];
    const boxWidth = Math.max(...lines.map(line => context.measureText(line).width)) + 24;
    const boxHeight = lines.length * 20 + 10;
    const boxX = hoverX + boxWidth + 16 > width ? hoverX - boxWidth - 10 : hoverX + 10;
    const boxY = pad.top + 6;
    context.fillStyle = surface; context.fillRect(boxX, boxY, boxWidth, boxHeight);
    context.strokeStyle = grid; context.strokeRect(boxX, boxY, boxWidth, boxHeight);
    lines.forEach((line, index) => { context.fillStyle = index < 2 || index >= active.length + 2 ? foreground : colors[active[index - 2]]; context.textAlign = 'left'; context.fillText(line, boxX + 12, boxY + 15 + index * 20); });
  }
}

function renderMonth(players, history) {
  const rows = [...players].sort((a, b) => totalWire(b.traffic) - totalWire(a.traffic));
  const hours = history?.hours || [];
  const start = hours.length ? hours[0].periodStartMillis : history?.periodStartMillis;
  const end = hours.length ? hours[hours.length - 1].periodEndMillis : history?.periodEndMillis;
  document.querySelector('#month-period').textContent = start && end ? `${formatDate(start)} - ${formatDate(end - 1)}` : '';
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
  document.querySelector('#details-subtitle').textContent = pt('details');
  const renderers = { transport: renderTransportDetails, server: renderServerDetails, entity_interpolation: renderEntityInterpolationDetails, recipe: renderRecipeDetails, chunk: renderChunkDetails, diagnostics: renderDiagnosticDetails };
  document.querySelector('#sections').innerHTML = sections.map(section => (renderers[section.id] || renderGenericDetails)(section)).join('');
  document.querySelectorAll('.pipeline-facts').forEach(facts => {
    const disclosure = document.createElement('details');
    const summary = document.createElement('summary');
    summary.textContent = pt('counters');
    facts.before(disclosure); disclosure.append(summary, facts);
  });
}

function detailSection(section, content, extraClass = '') {
  const title = section.id === 'jvm' ? jvmLabels[language].title : t('sections')[section.id] || section.title;
  const description = section.id === 'jvm' ? jvmLabels[language].description : t('descriptions')[section.id] || section.description;
  const started = sectionMetrics(section).counters_started_at_millis?.value;
  const period = section.id === 'transport' ? pt('transport') : section.id === 'server' ? pt('server') : pt('other');
  const dates = ['transport', 'server'].includes(section.id) ? `<p>${escapeText(pt('start'))}: ${escapeText(Number(started) > 0 ? formatDateTime(started) : pt('unknown'))} → ${escapeText(pt('end'))}: ${escapeText(formatDateTime(reportBundle.generatedAtMillis))} · ${escapeText(Intl.DateTimeFormat().resolvedOptions().timeZone)}</p>` : '';
  return `<section class="detail-band ${extraClass}"><div class="detail-heading"><div><h3>${escapeText(title)}</h3><p>${escapeText(description)}</p><p>${escapeText(period)}</p>${dates}</div></div>${content}</section>`;
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
  return detailSection(section, `<div class="chunk-detail-grid">${renderMetricCluster(t('hotspotEfficiency'), hotspot)}${renderMetricCluster(t('memoryFootprint'), memory, t('shadowCoverageNote'))}${renderMetricCluster(t('cacheReuse'), reuse)}</div>`, 'chunk-detail');
}

function renderMetricCluster(title, metrics, note = '') {
  const maximum = Math.max(1, ...metrics.map(item => Number(item.value || 0)));
  return `<div class="detail-cluster"><div class="cluster-heading"><h4>${escapeText(title)}</h4></div>${metrics.map(item => renderComparisonMetric(item, maximum)).join('')}${note ? `<p class="cluster-note">${escapeText(note)}</p>` : ''}</div>`;
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

function renderEntityInterpolationDetails(section) {
  const metrics = (section.metrics || []).map(item => ({ ...item, sectionId: 'entity_interpolation' }));
  return detailSection(section, renderMetricGroups(metrics));
}

function renderRecipeDetails(section) {
  const metrics = (section.metrics || []).map(item => ({ ...item, sectionId: 'recipe' }));
  return detailSection(section, renderMetricGroups(metrics));
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
    const source = contentSourceLabel(entry);
    const name = entry.payloadChannel || source || entry.packetClass?.split('.').pop() || `#${entry.rawPacketId}`;
    const detail = entry.payloadChannel ? entry.packetClass : `${entry.packetClass?.split('.').pop() || entry.protocol} · ${entry.flow}`;
    const sourceDetail = source && source !== name ? `<small>${escapeText(t('contentSource'))}: ${escapeText(source)}</small>` : '';
    return `<div class="bypass-row bypass-entry"><div class="bypass-identity"><strong>${escapeText(name)}</strong><small>${escapeText(detail)}</small>${sourceDetail}<small>${escapeText(entry.reason)}</small></div><div class="bypass-main"><meter class="visual-track" min="0" max="100" value="${(Number(entry.totalBytes || 0) * 100 / entryMaximum).toFixed(3)}" aria-label="${escapeAttribute(`${name} ${bytes(entry.totalBytes)}`)}"></meter><div class="bypass-values"><strong>${bytes(entry.totalBytes)}</strong><small>${new Intl.NumberFormat(language).format(Number(entry.totalPackets || 0))} ${escapeText(t('units').packets)}</small><small>${bytes(entry.windowBytes)} · ${new Intl.NumberFormat(language).format(Number(entry.windowPackets || 0))}</small></div></div></div>`;
  }).join('') || `<div class="empty">${escapeText(t('noData'))}</div>`;
  node.className = 'section bypass-section';
  node.innerHTML = `<h3>${escapeText(t('bypassAnalysis'))}</h3><p>${escapeText(t('bypassSubtitle'))}</p><div class="bypass-summary"><div><small>${escapeText(t('cumulativeTraffic'))}</small><strong>${bytes(bypass.totalBytes)}</strong><span>${new Intl.NumberFormat(language).format(Number(bypass.totalPackets || 0))} ${escapeText(t('units').packets)}</span></div><div><small>${escapeText(t('windowTraffic'))}</small><strong>${bytes(bypass.windowBytes)}</strong><span>${new Intl.NumberFormat(language).format(Number(bypass.windowPackets || 0))} ${escapeText(t('units').packets)}</span></div><div><small>${escapeText(t('capturedKeys'))}</small><strong>${entries.length} / ${new Intl.NumberFormat(language).format(Number(bypass.distinctKeys || 0))}</strong><span>${escapeText(t('distinctKeys'))}</span></div></div><div class="bypass-subheading"><h4>${escapeText(t('bypassSources'))}</h4></div><div class="bypass-list">${sourceRows}</div><div class="pagination" id="bypassSources-pagination"></div><div class="bypass-subheading"><h4>${escapeText(t('bypassEntries'))}</h4></div><div class="bypass-list">${entryRows}</div><div class="pagination" id="bypassEntries-pagination"></div>`;
  renderPagination('bypassSources', sources.length, () => renderBypass(bypass));
  renderPagination('bypassEntries', entries.length, () => renderBypass(bypass));
  node.insertAdjacentHTML('afterbegin', observationPeriod(bypass));
}

function observationPeriod(snapshot) {
  const started = Number(snapshot.countersStartedAtMillis);
  const observed = Number(snapshot.observedAtMillis);
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  return `<p>${escapeText(pt('start'))}: ${escapeText(started > 0 ? formatDateTime(started) : pt('unknown'))} → ${escapeText(cutoffLabels[language] || cutoffLabels['en-US'])}: ${escapeText(formatDateTime(observed > 0 ? observed : reportBundle.generatedAtMillis))} · ${escapeText(zone)}</p>${snapshot.windowStartedAtMillis ? `<p>${escapeText(t('windowTraffic'))}: ${escapeText(formatDateTime(snapshot.windowStartedAtMillis))} → ${escapeText(formatDateTime(observed))}</p>` : ''}`;
}

const sourceDetailLabels = {
  'zh-CN': { title: '来源流量明细', note: '累计为本次进程运行期间；窗口来自最近一次周期报告。传输字节为 BO 帧归因，批量包按份额估算，非逐来源线路实测；只包含已观测的出站来源。', totalRaw: '累计原始', totalActual: '累计传输（含估算）', windowRaw: '窗口原始', windowActual: '窗口传输（含估算）', estimated: '估算包', chunk: '区块协议包', ratio: '传输 / 原始', observed: '观测时间', unavailable: '旧报告未提供', window: '窗口', coverage: '已列来源占比' },
  'en-US': { title: 'Source traffic details', note: 'Totals cover this process run; the window is the last periodic observation. Transport bytes attribute BO frames, estimating shared batches, not per-source socket measurements. Only observed outbound sources are included.', totalRaw: 'Total raw', totalActual: 'Total transport (includes estimates)', windowRaw: 'Window raw', windowActual: 'Window transport (includes estimates)', estimated: 'Estimated packets', chunk: 'Chunk protocol packets', ratio: 'Transport / raw', observed: 'Observed at', unavailable: 'Unavailable in older report', window: 'Window', coverage: 'Listed source coverage' },
  'pt-BR': { title: 'Detalhes de tráfego por origem', note: 'Totais desta execução; a janela é a última observação periódica. Bytes de transporte atribuem quadros BO com estimativas para lotes compartilhados, não medições de rede por origem. Inclui apenas origens de saída observadas.', totalRaw: 'Total bruto', totalActual: 'Total transporte (inclui estimativas)', windowRaw: 'Janela bruta', windowActual: 'Janela transporte (inclui estimativas)', estimated: 'Pacotes estimados', chunk: 'Pacotes do protocolo de chunks', ratio: 'Transporte / bruto', observed: 'Observado em', unavailable: 'Indisponível no relatório antigo', window: 'Janela', coverage: 'Cobertura das origens listadas' }
};
let sourceTrafficOrder = 'rawBytes';
function renderSourceTraffic(snapshot) {
  const node = document.querySelector('#source-traffic-section');
  if (!snapshot) {
    node.className = 'section bypass-section';
    node.innerHTML = `<h3>${escapeText(rt('sourceTitle'))}</h3><p>${escapeText(rt('sourceMissing'))}</p>`;
    return;
  }
  const labels = sourceDetailLabels[language] || sourceDetailLabels['en-US'];
  const detailed = Number.isFinite(snapshot.actualBytes);
  const orders = { rawBytes: 'totalRaw', actualBytes: 'totalActual', windowRawBytes: 'windowRaw', windowActualBytes: 'windowActual' };
  if (!detailed) sourceTrafficOrder = 'rawBytes';
  const entries = [...(Array.isArray(snapshot.entries) ? snapshot.entries : [])].sort((a, b) => Number(b[sourceTrafficOrder] || 0) - Number(a[sourceTrafficOrder] || 0) || String(a.sourceKey).localeCompare(String(b.sourceKey)));
  const totalField = sourceTrafficOrder;
  const listedBytes = entries.reduce((sum, entry) => sum + Number(entry[totalField] || 0), 0);
  const maximum = Math.max(1, ...entries.map(entry => Number(entry[totalField] || 0)));
  const total = Number(snapshot[totalField] || 0);
  const rows = pageSlice(entries, pages.sources).map(entry => {
    const amount = Number(entry[totalField] || 0);
    const name = entry.sourceKey || entry.packetClassName || '#unknown';
    const detail = detailed ? `<small>${escapeText(labels.totalRaw)}: ${bytes(entry.rawBytes)} · ${escapeText(labels.totalActual)}: ${bytes(entry.actualBytes)} · ${escapeText(labels.ratio)}: ${Number(entry.rawBytes) > 0 ? (Number(entry.actualBytes) / Number(entry.rawBytes)).toFixed(3) + '×' : '—'}</small><small>${escapeText(labels.windowRaw)}: ${bytes(entry.windowRawBytes)} · ${escapeText(labels.windowActual)}: ${bytes(entry.windowActualBytes)} · ${new Intl.NumberFormat(language).format(Number(entry.windowPackets || 0))} ${escapeText(t('units').packets)}</small><small>${escapeText(labels.chunk)}: ${Number(entry.chunkWrappedPackets || 0)} · ${escapeText(labels.estimated)}: ${Number(entry.estimatedPackets || 0)}</small><small>${escapeText(entry.lastPath || '')} / ${escapeText(entry.lastFrame || '')}</small>` : '';
    return `<div class="bypass-row bypass-entry"><div class="bypass-identity"><strong>${escapeText(name)}</strong><small>${escapeText(entry.packetClassName || '')}${Number.isFinite(entry.rawPacketId) ? ' #' + entry.rawPacketId : ''}</small>${detail}</div><div class="bypass-main"><meter class="visual-track" min="0" max="100" value="${(100 * amount / maximum).toFixed(3)}"></meter><div class="bypass-values"><strong>${bytes(amount)}</strong><small>${total > 0 ? (100 * amount / total).toFixed(2) : '0.00'}%</small><small>${new Intl.NumberFormat(language).format(Number(entry.packets || 0))} ${escapeText(t('units').packets)}</small></div></div></div>`;
  }).join('') || `<div class="empty">${escapeText(t('noData'))}</div>`;
  const missingKeys = Math.max(0, Number(snapshot.distinctKeys || 0) - entries.length);
  const missingBytes = Math.max(0, total - listedBytes);
  node.className = 'section bypass-section';
  const controls = detailed ? `<select id="source-traffic-order" aria-label="${escapeText(labels.title)}">${Object.entries(orders).map(([key, label]) => `<option value="${key}" ${key === sourceTrafficOrder ? 'selected' : ''}>${escapeText(labels[label])}</option>`).join('')}</select>` : '';
  node.innerHTML = `<h3>${escapeText(labels.title)}</h3><p>${escapeText(detailed ? labels.note : rt('sourceNote') + ' ' + sourcePeriodLabel[language])}</p>${controls}<div class="bypass-summary"><div><small>${escapeText(labels[orders[totalField]])}</small><strong>${bytes(total)}</strong><span>${escapeText(labels.coverage)}: ${total > 0 ? (100 * listedBytes / total).toFixed(2) : '0.00'}%</span></div><div><small>${escapeText(rt('sourceKeys'))}</small><strong>${entries.length} / ${new Intl.NumberFormat(language).format(Number(snapshot.distinctKeys || 0))}</strong></div><div><small>${escapeText(t('unlistedKeys'))}</small><strong>${bytes(missingBytes)}</strong><span>${new Intl.NumberFormat(language).format(missingKeys)} ${escapeText(t('units').entries)}</span></div></div><div class="bypass-list">${rows}</div><div class="pagination" id="sources-pagination"></div>`;
  node.querySelector('#source-traffic-order')?.addEventListener('change', event => { sourceTrafficOrder = event.target.value; pages.sources.page = 1; renderSourceTraffic(snapshot); });
  renderPagination('sources', entries.length, () => renderSourceTraffic(snapshot));
  node.insertAdjacentHTML('afterbegin', observationPeriod(snapshot));
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
  const sourceKey = String(entry.sourceKey || '').trim().toLowerCase();
  if (sourceKey.startsWith('mod:')) return sourceKey.slice(4) || t('sharedProtocol');
  if (sourceKey.startsWith('mods:')) return `${t('multipleSources')} · ${sourceKey.slice(5).replaceAll(',', ' + ')}`;
  if (sourceKey.startsWith('recipe_namespaces:') || sourceKey.startsWith('registry_namespaces:')) {
    const namespaces = sourceKey.slice(sourceKey.indexOf(':') + 1);
    if (namespaces && !namespaces.includes(',')) return namespaces;
    if (namespaces) return `${t('multipleSources')} · ${namespaces.replaceAll(',', ' + ')}`;
  }
  const channel = String(entry.payloadChannel || '').trim().toLowerCase();
  if (channel.includes(':')) {
    const namespace = channel.slice(0, channel.indexOf(':'));
    return namespace === 'minecraft' ? `${t('sharedProtocol')} · ${t('protocolControl')}` : namespace;
  }
  if (channel) return channel;
  const packetClass = String(entry.packetClass || '');
  if (packetClass.startsWith('net.minecraft.')) return `${t('sharedProtocol')} · ${t(vanillaPacketFamily(entry))}`;
  return t('thirdPartyPacket');
}

function contentSourceLabel(entry) {
  const sourceKey = String(entry.sourceKey || '').trim();
  if (!sourceKey || sourceKey.startsWith('packet:')) return '';
  if (sourceKey.startsWith('mod:')) return sourceKey.slice(4);
  if (sourceKey.startsWith('mods:')) return `${t('multipleSources')} · ${sourceKey.slice(5).replaceAll(',', ' + ')}`;
  if (sourceKey.startsWith('recipe_namespaces:') || sourceKey.startsWith('registry_namespaces:')) {
    const namespaces = sourceKey.slice(sourceKey.indexOf(':') + 1);
    return namespaces.includes(',') ? `${t('multipleSources')} · ${namespaces.replaceAll(',', ' + ')}` : namespaces;
  }
  return sourceKey;
}

function vanillaPacketFamily(entry) {
  const packetClass = String(entry.packetClass || '');
  const protocol = String(entry.protocol || '').toUpperCase();
  if (/Recipe|Registry|UpdateTags|KnownPack|KnownPacks/.test(packetClass)) return 'familyRecipeRegistry';
  if (/Sound|Particle|LevelEvent|Explosion/.test(packetClass)) return 'familyEffects';
  if (/Entity|Mob|Teleport|RotateHead|SetEquipment|UpdateAttributes|Animate|Damage|Hurt/.test(packetClass)) return 'familyEntity';
  if (/Block|Chunk|Light|SetTime/.test(packetClass)) return 'familyWorld';
  if (/Container|Screen|Chat|TabList|Command|Score|Boss|PlayerInfo|SetHealth|SetExperience/.test(packetClass)) return 'familyPlayerUi';
  if (protocol !== 'PLAY' || /Login|KeepAlive|Disconnect|Ping|Pong/.test(packetClass)) return 'familyConnection';
  return 'familyOther';
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
    return `<div class="metric-group scope-${escapeAttribute(group.scope)}"><div class="metric-group-title">${escapeText(t('scopes')[group.scope] || jvmLabels[language][group.scope] || group.scope)}</div>${rows}</div>`;
  }).join('')}</div>`;
}

function renderVisualMetric(item, maximumBytes) {
  const label = escapeText(metricLabel(item));
  const stage = escapeText(t('stages')[item.stage] || jvmLabels[language][item.stage] || item.stage);
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
function metricLabel(item) { const short = item.id.split('.').pop(); return (item.sectionId === 'recipe' && recipeLabels[language]?.[item.id]) || (item.sectionId === 'entity_interpolation' && entityInterpolationLabels[language]?.[item.id]) || reportLabels[language]?.[item.id] || metricLabels[language]?.[item.id] || metricLabels[language]?.[short] || item.label; }
function metricValue(item) { if (item.unit === 'timestamp') return formatDateTime(item.value); if (item.unit === 'bytes') return bytes(item.value); if (item.unit === 'boolean') return Number(item.value) ? t('enabled') : t('disabled'); return `${new Intl.NumberFormat(language).format(Number(item.value || 0))} ${t('units')[item.unit] || jvmLabels[language][item.unit] || item.unit}`; }
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
document.querySelector('#trend-version').addEventListener('change', () => { chartHoverIndex = -1; render(); });
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
