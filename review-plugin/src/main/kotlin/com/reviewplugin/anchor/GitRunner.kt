package com.reviewplugin.anchor

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import java.io.File

class GitRunner(private val projectRoot: String) {

    fun currentCommit(): String {
        return runGit("rev-parse", "HEAD").trim()
    }

    fun diffUnified(commit: String, file: String): String {
        return runGit("diff", commit, "--", file)
    }

    fun fileContentAtCommit(commit: String, file: String): String {
        return runGit("show", "$commit:$file")
    }

    fun isGitRepo(): Boolean {
        return try {
            val result = runGitWithExitCode("rev-parse", "--git-dir")
            result.first == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun runGit(vararg args: String): String {
        val (exitCode, output) = runGitWithExitCode(*args)
        if (exitCode != 0) {
            throw GitException("git ${args.joinToString(" ")} failed with exit code $exitCode: $output")
        }
        return output
    }

    private fun runGitWithExitCode(vararg args: String): Pair<Int, String> {
        val cmd = GeneralCommandLine("git", *args)
        cmd.workDirectory = File(projectRoot)
        cmd.charset = Charsets.UTF_8
        val handler = CapturingProcessHandler(cmd)
        val result = handler.runProcess(30_000)
        val output = result.stdout + result.stderr
        return Pair(result.exitCode, output)
    }
}

class GitException(message: String) : RuntimeException(message)
