// WebSocket 연결
let ws = null;
let reconnectInterval = null;
let logEventSource = null;

// 컨테이너 아이콘 매핑
const containerIcons = {
    'springboot-app': '🌱',
    'mysql-db': '🗄️',
    'nginx': '🌐',
    'prometheus': '📊',
    'grafana': '📈',
    'test-nginx-1': '🧪',
    'test-nginx-2': '🧪',
    'test-nginx-3': '🧪',
    'demo-app': '🚀'
};

// 초기화
document.addEventListener('DOMContentLoaded', () => {
    connectWebSocket();
    updateTime();
    setInterval(updateTime, 1000);
    loadInitialData();
    setupModalClose();
});

// 현재 시간 업데이트
function updateTime() {
    const now = new Date();
    document.getElementById('currentTime').textContent = now.toLocaleTimeString('ko-KR');
}

// WebSocket 연결
function connectWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const host = window.location.host;
    const wsUrl = `${protocol}//${host}/ws/monitoring`;

    addLog('info', `WebSocket 연결 시도: ${wsUrl}`);

    ws = new WebSocket(wsUrl);

    ws.onopen = () => {
        console.log('WebSocket connected');
        document.getElementById('connectionStatus').classList.add('connected');
        document.getElementById('connectionText').textContent = '연결됨';
        addLog('success', 'WebSocket 연결 성공');

        if (reconnectInterval) {
            clearInterval(reconnectInterval);
            reconnectInterval = null;
        }
    };

    ws.onmessage = (event) => {
        try {
            const message = JSON.parse(event.data);
            console.log('Received:', message);
            handleWebSocketMessage(message);
        } catch (error) {
            console.error('Failed to parse message:', error);
        }
    };

    ws.onerror = (error) => {
        console.error('WebSocket error:', error);
        addLog('error', 'WebSocket 에러 발생');
    };

    ws.onclose = () => {
        console.log('WebSocket disconnected');
        document.getElementById('connectionStatus').classList.remove('connected');
        document.getElementById('connectionText').textContent = '연결 끊김';
        addLog('warn', 'WebSocket 연결 끊김. 재연결 시도 중...');

        if (!reconnectInterval) {
            reconnectInterval = setInterval(() => {
                connectWebSocket();
            }, 5000);
        }
    };
}

// WebSocket 메시지 처리
function handleWebSocketMessage(message) {
    switch (message.type) {
        case 'initial_status':
            updateAllContainers(message.data);
            break;
        case 'container_status_update':
            updateContainer(message.data);
            break;
        case 'docker_event':
            handleDockerEvent(message.data);
            break;
        case 'github_push':
            handleGithubPush(message.data);
            break;
        case 'github_workflow':
            handleGithubWorkflow(message.data);
            break;
        default:
            console.log('Unknown message type:', message.type);
    }
}

// 초기 데이터 로드
async function loadInitialData() {
    try {
        const response = await fetch('/api/monitoring/containers');
        const containers = await response.json();
        updateAllContainers(containers);
        addLog('info', '초기 컨테이너 상태 로드 완료');
    } catch (error) {
        console.error('Failed to load initial data:', error);
        addLog('error', '초기 데이터 로드 실패');
    }
}

// 모든 컨테이너 업데이트
function updateAllContainers(containers) {
    const grid = document.getElementById('containerGrid');
    grid.innerHTML = '';

    Object.entries(containers).forEach(([name, status]) => {
        const card = createContainerCard(name, status);
        grid.appendChild(card);
    });
}

// 개별 컨테이너 업데이트
function updateContainer(status) {
    const existingCard = document.getElementById(`container-${status.containerName}`);

    if (existingCard) {
        existingCard.replaceWith(createContainerCard(status.containerName, status));
    } else {
        const grid = document.getElementById('containerGrid');
        grid.appendChild(createContainerCard(status.containerName, status));
    }
}

// 컨테이너 카드 생성
function createContainerCard(name, status) {
    const card = document.createElement('div');
    card.id = `container-${name}`;
    card.className = `container-card ${status.phase || 'unknown'}`;

    // ✅ 클릭 이벤트 추가
    card.onclick = () => openLogModal(name);

    const icon = containerIcons[name] || '📦';
    const phase = status.phase || 'unknown';
    const progress = status.progress || 0;

    card.innerHTML = `
        <div class="container-icon">${icon}</div>
        <div class="container-name">${name}</div>
        <div class="container-status ${phase}">${getPhaseText(phase)}</div>
        <div class="progress-bar">
            <div class="progress-fill" style="width: ${progress}%"></div>
        </div>
        <div class="container-info">
            ${status.cpu ? `CPU: ${status.cpu} | ` : ''}
            ${status.memory ? `MEM: ${status.memory} | ` : ''}
            ${status.uptime ? `가동: ${status.uptime}` : ''}
        </div>
    `;

    return card;
}

// Phase 텍스트 변환
function getPhaseText(phase) {
    const phaseMap = {
        'creating': '생성 중',
        'starting': '시작 중',
        'running': '실행 중',
        'stopping': '중지 중',
        'stopped': '중지됨',
        'removed': '삭제됨'
    };
    return phaseMap[phase] || phase;
}

// Docker 이벤트 처리
function handleDockerEvent(data) {
    const eventType = data.eventType || data.status;
    addLog('info', `${data.containerName}: ${eventType}`);
}

// GitHub Push 이벤트 처리
function handleGithubPush(data) {
    const container = document.getElementById('githubEvents');

    const eventItem = document.createElement('div');
    eventItem.className = 'event-item';
    eventItem.innerHTML = `
        <div class="event-time">${new Date(data.timestamp).toLocaleTimeString('ko-KR')}</div>
        <div class="event-message">
            <strong>📝 Push 이벤트</strong><br>
            브랜치: ${data.branch}<br>
            메시지: ${data.commitMessage}<br>
            작성자: ${data.pusher}
        </div>
    `;

    const noData = container.querySelector('.no-data');
    if (noData) {
        noData.remove();
    }

    container.insertBefore(eventItem, container.firstChild);

    addLog('success', `GitHub Push: ${data.branch} - ${data.commitMessage}`);
}

// GitHub Workflow 이벤트 처리
function handleGithubWorkflow(data) {
    const statusText = data.conclusion === 'success' ? '성공' :
        data.conclusion === 'failure' ? '실패' :
            data.status;

    addLog(
        data.conclusion === 'success' ? 'success' : 'warn',
        `GitHub Actions: ${data.workflowName} - ${statusText}`
    );
}

// 로그 추가
function addLog(level, message) {
    const logViewer = document.getElementById('logViewer');
    const now = new Date();
    const timeStr = now.toTimeString().split(' ')[0];

    const logLine = document.createElement('div');
    logLine.className = `log-line ${level}`;
    logLine.textContent = `[${timeStr}] ${message}`;

    logViewer.appendChild(logLine);
    logViewer.scrollTop = logViewer.scrollHeight;

    if (logViewer.children.length > 100) {
        logViewer.removeChild(logViewer.firstChild);
    }
}

// ============================================
// 로그 모달 기능
// ============================================

function openLogModal(containerName) {
    const modal = document.getElementById('logModal');
    const title = document.getElementById('modalTitle');
    const logContainer = document.getElementById('logContainer');

    // 모달 표시
    modal.classList.add('active');
    title.textContent = `${containerIcons[containerName] || '📦'} ${containerName} - 실시간 로그`;
    logContainer.innerHTML = '<div class="log-loading">로그 로딩 중...</div>';

    // 기존 EventSource 종료
    if (logEventSource) {
        logEventSource.close();
    }

    // SSE로 실시간 로그 스트리밍
    logEventSource = new EventSource(`/api/logs/${containerName}/stream`);

    logContainer.innerHTML = '<pre></pre>';
    const logPre = logContainer.querySelector('pre');

    logEventSource.addEventListener('log', (event) => {
        logPre.textContent += event.data;
        // 자동 스크롤
        logContainer.scrollTop = logContainer.scrollHeight;
    });

    logEventSource.onerror = (error) => {
        console.error('Log stream error:', error);
        logPre.textContent += '\n\n❌ 로그 스트리밍 오류 발생\n';
        logEventSource.close();
    };
}

function closeLogModal() {
    const modal = document.getElementById('logModal');
    modal.classList.remove('active');

    // EventSource 종료
    if (logEventSource) {
        logEventSource.close();
        logEventSource = null;
    }
}

function setupModalClose() {
    const modal = document.getElementById('logModal');

    // X 버튼 클릭
    document.getElementById('modalCloseBtn').onclick = closeLogModal;

    // 모달 배경 클릭
    modal.onclick = (e) => {
        if (e.target === modal) {
            closeLogModal();
        }
    };

    // ESC 키
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && modal.classList.contains('active')) {
            closeLogModal();
        }
    });
}