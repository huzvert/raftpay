// RaftPay Dashboard
// Works both locally and remotely (via ngrok etc.)

const POLL_INTERVAL = 1500;
let previousLeader = null;
let nodeIds = [];

// ========== Initialization ==========

function init() {
    addEvent('Dashboard started. Polling cluster...', '');
    pollCluster();
    setInterval(pollCluster, POLL_INTERVAL);
    setupForms();
}

// ========== Polling ==========

async function pollCluster() {
    try {
        // Use the server-side endpoint that queries all nodes
        const response = await fetch(`/api/cluster/all-status`, {
            signal: AbortSignal.timeout(3000)
        });
        const allStatus = await response.json();

        // Render node cards if we haven't yet or node count changed
        if (nodeIds.length !== allStatus.length) {
            nodeIds = allStatus.map(s => s.nodeId);
            renderNodeCards(allStatus);
        }

        let nodesUp = 0;
        let currentLeader = null;
        let maxTerm = 0;

        for (const status of allStatus) {
            if (status.reachable) {
                nodesUp++;
                updateNodeCard(status.nodeId, status);
                if (status.currentTerm > maxTerm) {
                    maxTerm = status.currentTerm;
                }
                if (status.state === 'LEADER') {
                    currentLeader = status.nodeId;
                }
            } else {
                updateNodeCard(status.nodeId, null);
            }
        }

        // Update header
        document.getElementById('cluster-term').textContent = `Term: ${maxTerm}`;
        document.getElementById('cluster-leader').textContent = `Leader: ${currentLeader || 'NONE'}`;
        document.getElementById('cluster-health').textContent = `Nodes: ${nodesUp}/${allStatus.length}`;

        // Detect leader changes
        if (currentLeader !== previousLeader && previousLeader !== null) {
            if (currentLeader) {
                addEvent(`Leader changed: ${previousLeader || 'none'} → ${currentLeader}`, 'warning');
            } else {
                addEvent('No leader! Cluster may be electing...', 'error');
            }
        }
        previousLeader = currentLeader;

        // Fetch accounts and log from this node (via relative URL)
        fetchAccounts();
        fetchLog();

    } catch (e) {
        // Can't reach the server at all
        document.getElementById('cluster-leader').textContent = 'Leader: UNREACHABLE';
    }
}

// ========== Node Cards ==========

function renderNodeCards(statuses) {
    const grid = document.getElementById('nodes-grid');
    grid.innerHTML = statuses.map(s => `
        <div class="node-card down" id="card-${s.nodeId}">
            <div class="node-id">${s.nodeId}</div>
            <div class="node-state">OFFLINE</div>
            <div class="node-details">
                <div>Term: -</div>
                <div>Log: -</div>
                <div>Commit: -</div>
            </div>
        </div>
    `).join('');
}

function updateNodeCard(nodeId, status) {
    const card = document.getElementById(`card-${nodeId}`);
    if (!card) return;

    if (!status) {
        card.className = 'node-card down';
        card.querySelector('.node-state').textContent = 'OFFLINE';
        card.querySelector('.node-details').innerHTML = `
            <div>Term: -</div>
            <div>Log: -</div>
            <div>Commit: -</div>
        `;
        return;
    }

    const state = status.state.toLowerCase();
    card.className = `node-card ${state}`;
    card.querySelector('.node-state').textContent = status.state;
    card.querySelector('.node-details').innerHTML = `
        <div>Term: ${status.currentTerm}</div>
        <div>Log: ${status.logSize} entries</div>
        <div>Commit: ${status.commitIndex}</div>
        <div>Applied: ${status.lastApplied}</div>
        ${status.accountCount !== undefined ? `<div>Accounts: ${status.accountCount}</div>` : ''}
    `;
}

// ========== Accounts Table ==========

async function fetchAccounts() {
    try {
        const response = await fetch(`/api/accounts`, {
            signal: AbortSignal.timeout(2000)
        });
        const result = await response.json();

        const tbody = document.getElementById('accounts-body');
        if (!result.data || result.data.length === 0) {
            tbody.innerHTML = '<tr><td colspan="3" class="empty">No accounts yet</td></tr>';
            return;
        }

        tbody.innerHTML = result.data.map(account => `
            <tr>
                <td>${account.accountId}</td>
                <td>${account.ownerName}</td>
                <td>$${account.balance.toFixed(2)}</td>
            </tr>
        `).join('');
    } catch (e) {
        // Silently ignore
    }
}

// ========== Raft Log Table ==========

async function fetchLog() {
    try {
        const response = await fetch(`/api/cluster/log`, {
            signal: AbortSignal.timeout(2000)
        });
        const result = await response.json();

        const tbody = document.getElementById('log-body');
        if (!result.data || result.data.length === 0) {
            tbody.innerHTML = '<tr><td colspan="5" class="empty">No log entries</td></tr>';
            return;
        }

        tbody.innerHTML = result.data.map(entry => {
            let cmdDisplay = '';
            try {
                const cmd = JSON.parse(entry.command);
                cmdDisplay = cmd.type || entry.command;
                if (cmd.accountId) cmdDisplay += ` (${cmd.accountId}`;
                if (cmd.toAccountId) cmdDisplay += ` → ${cmd.toAccountId}`;
                if (cmd.amountCents) cmdDisplay += ` $${(cmd.amountCents / 100).toFixed(2)}`;
                if (cmd.accountId) cmdDisplay += ')';
            } catch {
                cmdDisplay = entry.command || '[binary]';
            }

            return `
                <tr>
                    <td>${entry.index}</td>
                    <td>${entry.term}</td>
                    <td>${cmdDisplay}</td>
                    <td><span class="badge ${entry.committed ? 'yes' : 'no'}">${entry.committed ? 'YES' : 'NO'}</span></td>
                    <td><span class="badge ${entry.applied ? 'yes' : 'no'}">${entry.applied ? 'YES' : 'NO'}</span></td>
                </tr>
            `;
        }).join('');
    } catch (e) {
        // Silently ignore
    }
}

// ========== Forms ==========

function setupForms() {
    document.getElementById('create-account-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        const data = {
            accountId: document.getElementById('new-account-id').value,
            ownerName: document.getElementById('new-owner').value,
            initialBalance: parseFloat(document.getElementById('new-balance').value) || 0
        };

        try {
            const res = await fetch(`/api/accounts`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            const result = await res.json();
            addEvent(`Create account: ${result.message}`, result.success ? 'success' : 'error');
            e.target.reset();
        } catch (err) {
            addEvent(`Create account failed: ${err.message}`, 'error');
        }
    });

    document.getElementById('transfer-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        const data = {
            fromAccountId: document.getElementById('transfer-from').value,
            toAccountId: document.getElementById('transfer-to').value,
            amount: parseFloat(document.getElementById('transfer-amount').value)
        };

        try {
            const res = await fetch(`/api/transfers`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            const result = await res.json();
            addEvent(`Transfer: ${result.message}`, result.success ? 'success' : 'error');
            e.target.reset();
        } catch (err) {
            addEvent(`Transfer failed: ${err.message}`, 'error');
        }
    });

    document.getElementById('deposit-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        const accountId = document.getElementById('deposit-account').value;
        const amount = parseFloat(document.getElementById('deposit-amount').value);

        try {
            const res = await fetch(`/api/accounts/${accountId}/deposit`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ amount })
            });
            const result = await res.json();
            addEvent(`Deposit: ${result.message}`, result.success ? 'success' : 'error');
            e.target.reset();
        } catch (err) {
            addEvent(`Deposit failed: ${err.message}`, 'error');
        }
    });

    document.getElementById('withdraw-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        const accountId = document.getElementById('withdraw-account').value;
        const amount = parseFloat(document.getElementById('withdraw-amount').value);

        try {
            const res = await fetch(`/api/accounts/${accountId}/withdraw`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ amount })
            });
            const result = await res.json();
            addEvent(`Withdraw: ${result.message}`, result.success ? 'success' : 'error');
            e.target.reset();
        } catch (err) {
            addEvent(`Withdraw failed: ${err.message}`, 'error');
        }
    });
}

// ========== Event Log ==========

function addEvent(message, type = '') {
    const list = document.getElementById('events-list');
    const time = new Date().toLocaleTimeString();
    const div = document.createElement('div');
    div.className = `event ${type}`;
    div.textContent = `[${time}] ${message}`;
    list.insertBefore(div, list.firstChild);

    // Keep max 50 events
    while (list.children.length > 50) {
        list.removeChild(list.lastChild);
    }
}

// ========== Start ==========

document.addEventListener('DOMContentLoaded', init);
