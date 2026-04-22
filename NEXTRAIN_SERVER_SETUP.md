# NextTrain Server — Separate Repository Setup

This guide explains how to create the [NextTrain-Server](https://github.com/frankie2784/NextTrain-Server) repository from the original codebase.

## Option A: Using git subtree (simplest for existing repos)

If you already have the NextTrain repo with both web frontend and server code:

```bash
# Create a new repo on GitHub called NextTrain-Server

# Clone the original NextTrain repo
git clone https://github.com/frankie2784/NextTrain.git
cd NextTrain

# Add NextTrain-Server as a remote
git remote add server-repo https://github.com/frankie2784/NextTrain-Server.git

# Push the server/ directory as a separate repo
git subtree push --prefix server server-repo main
```

## Option B: Clean split from scratch

```bash
# Clone the original repo
git clone https://github.com/frankie2784/NextTrain.git nexttrain-server
cd nexttrain-server

# Remove web frontend, keep only server
git rm -r web/ .github/
git rm README.md .gitignore
git commit -m "Remove web frontend — server-only repo"

# Now the repo has just server/

# Create a new empty repo on GitHub called NextTrain-Server
# Then push:
git remote set-url origin https://github.com/frankie2784/NextTrain-Server.git
git push -u origin main
```

## Option C: Manual copy

1. Create a new repo: `NextTrain-Server`
2. Clone it locally: `git clone https://github.com/frankie2784/NextTrain-Server.git`
3. Copy the `server/` directory contents into the root of the new repo
4. Copy `server/README.md` to `README.md`
5. Copy `server/.env.example` to `.env.example`
6. Create a `.gitignore`:

```
__pycache__/
*.pyc
.env
*.db
data/
.DS_Store
.vscode/
.idea/
```

7. Commit and push:

```bash
git add .
git commit -m "Initial commit: NextTrain Flask server"
git push -u origin main
```

## After Split

The NextTrain repo (this branch, `claude/cloudflare-tunnel-cors`) now:
- ✅ Contains only the web frontend (`web/`) + GitHub Actions workflow
- ✅ Links to NextTrain-Server in the README
- ✅ No server code, no Android app

The NextTrain-Server repo will:
- ✅ Contain Flask server + Docker Compose setup
- ✅ Be independently deployable
- ✅ Have its own documentation and versioning
