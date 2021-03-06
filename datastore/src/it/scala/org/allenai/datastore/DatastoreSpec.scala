package org.allenai.datastore

import java.net.URL
import java.nio.file.{ Files, Path, StandardCopyOption }
import java.util.UUID
import java.util.zip.ZipFile

import org.allenai.common.testkit.UnitSpec
import org.allenai.common.{ Logging, Resource, Timing }
import org.apache.commons.io.filefilter.TrueFileFilter
import org.apache.commons.io.{ FileUtils, IOUtils }

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.language.postfixOps

class DatastoreSpec extends UnitSpec with Logging {
  private val group = "org.allenai.datastore.test"

  private def copyTestFiles: Path = {
    // copy the zip file with the tests from the jar into a temp file
    val zipfile = Files.createTempFile("ai2-datastore-test", ".zip")
    TempCleanup.remember(zipfile)
    Resource.using(getClass.getResourceAsStream("testfiles.zip")) { is =>
      Files.copy(is, zipfile, StandardCopyOption.REPLACE_EXISTING)
    }

    // unzip the zipfile
    val zipdir = Files.createTempDirectory("ai2-datastore-test")
    TempCleanup.remember(zipdir)
    Resource.using(new ZipFile(zipfile.toFile)) { zipFile =>
      val entries = zipFile.entries()
      while (entries.hasMoreElements) {
        val entry = entries.nextElement()
        val pathForEntry = zipdir.resolve(entry.getName)
        if (entry.isDirectory) {
          Files.createDirectories(pathForEntry)
        } else {
          Files.createDirectories(pathForEntry.getParent)
          Resource.using(zipFile.getInputStream(entry))(Files.copy(_, pathForEntry))
        }
      }
    }

    Files.delete(zipfile)
    TempCleanup.forget(zipfile)

    zipdir
  }

  def makeTestDatastore: Datastore = {
    val storename = "test-" + UUID.randomUUID().toString
    val datastore = Datastore(storename)
    datastore.createBucketIfNotExists()

    // Wait 10 seconds, because bucket creation is not instantaneous in S3
    logger.info("Waiting 10 seconds for bucket creation...")
    Thread.sleep(10000)

    datastore
  }

  def deleteDatastore(datastore: Datastore): Unit = {
    // delete the cache
    datastore.wipeCache()

    // delete everything in the bucket
    val s3 = datastore.s3
    val bucket = datastore.bucketName
    var listing = s3.listObjects(bucket)
    while (listing != null) {
      listing.getObjectSummaries.toList.foreach(summary =>
        s3.deleteObject(bucket, summary.getKey))

      listing = if (listing.isTruncated) s3.listNextBatchOfObjects(listing) else null
    }

    // delete the bucket
    s3.deleteBucket(bucket)
  }

  "DataStore" should "upload and download files" in {
    val testfilesDir = copyTestFiles
    val filenames = Seq("small_file_at_root.bin", "medium_file_at_root.bin", "big_file_at_root.bin")
    val datastore = makeTestDatastore
    try {
      for (filename <- filenames) {
        val fullFilenameString = testfilesDir.toString + "/" + filename
        datastore.publishFile(fullFilenameString, group, filename, 13, false)
      }

      for (filename <- filenames) {
        val path = datastore.filePath(group, filename, 13)
        assert(FileUtils.contentEquals(path.toFile, testfilesDir.resolve(filename).toFile))
      }
    } finally {
      deleteDatastore(datastore)
    }
  }

  it should "fail to download files that don't exist" in {
    val testfile = "medium_file_at_root.bin"
    val testfilesDir = copyTestFiles
    val datastore = makeTestDatastore
    try {
      datastore.publishFile(testfilesDir.resolve(testfile), group, testfile, 83, false)

      intercept[datastore.DoesNotExistException] {
        datastore.filePath(group, testfile, 13)
      }

      intercept[datastore.DoesNotExistException] {
        datastore.filePath(group, testfile + ".does_not_exist", 83)
      }

      intercept[datastore.DoesNotExistException] {
        datastore.filePath(group + ".does_not_exist", testfile, 83)
      }
    } finally {
      deleteDatastore(datastore)
    }
  }

  it should "download the same file only once even if requested twice" in {
    val testfile = "big_file_at_root.bin"
    val testfilesDir = copyTestFiles
    val datastore = makeTestDatastore
    try {
      val testfilePath = testfilesDir.resolve(testfile)
      datastore.publishFile(testfilePath, group, testfile, 83, false)

      def downloadAndCheckFile(): Unit = {
        val path = datastore.filePath(group, testfile, 83)
        assert(FileUtils.contentEquals(path.toFile, testfilePath.toFile))
      }

      def time(f: => Unit) = {
        val start = System.nanoTime()
        f
        System.nanoTime() - start
      }

      val firstTime = time(downloadAndCheckFile)
      val secondTime = time(downloadAndCheckFile)
      assert(firstTime > secondTime)
    } finally {
      deleteDatastore(datastore)
    }
  }

  it should "download the same file only once even if requested twice in parallel" in {
    val testfile = "big_file_at_root.bin"
    val testfilesDir = copyTestFiles
    val datastore = makeTestDatastore
    try {
      val testfilePath = testfilesDir.resolve(testfile)
      datastore.publishFile(testfilePath, group, testfile, 83, false)

      def downloadAndCheckFile(): Unit = {
        val path = datastore.filePath(group, testfile, 83)
        assert(path.toFile.length() === testfilePath.toFile.length())
      }

      val delayInMs: Long = 250
      val firstTime = Future { Timing.time(downloadAndCheckFile) }
      Thread.sleep(delayInMs)
      val secondTime = Future { Timing.time(downloadAndCheckFile) }

      // The second one has to wait for the first one to finish before it can
      // finish, so it should be slower.
      assert(Await.result(firstTime, 10 minutes) < Await.result(secondTime, 10 minutes) + delayInMs.milliseconds)
    } finally {
      deleteDatastore(datastore)
    }
  }

  it should "upload and download a directory" in {
    def listOfFiles(dir: Path) =
      FileUtils.listFilesAndDirs(
        dir.toFile,
        TrueFileFilter.INSTANCE,
        TrueFileFilter.INSTANCE
      ).toList.sorted.map { p =>
        dir.relativize(p.toPath)
      }

    val testfilesDir = copyTestFiles
    val testfiles = listOfFiles(testfilesDir)
    val datastore = makeTestDatastore
    try {
      datastore.publishDirectory(testfilesDir, group, "TestfilesDir", 11, false)

      val datastoreDir = datastore.directoryPath(group, "TestfilesDir", 11)
      val datastoreFiles = listOfFiles(datastoreDir)

      assert(testfiles === datastoreFiles)
    } finally {
      deleteDatastore(datastore)
    }
  }

  it should "download files using a URL" in {
    val testfilesDir = copyTestFiles
    val filenames = Seq("small_file_at_root.bin", "medium_file_at_root.bin", "big_file_at_root.bin")
    val datastore = makeTestDatastore
    try {
      for (filename <- filenames) {
        val fullFilenameString = testfilesDir.toString + "/" + filename
        datastore.publishFile(fullFilenameString, group, filename, 13, false)
      }

      for (filename <- filenames) {
        val filenameWithVersion = filename.replace(".bin", "-v13.bin")
        val u = new URL(s"datastore://${datastore.name}/$group/$filenameWithVersion")

        val original = IOUtils.toByteArray(Files.newInputStream(testfilesDir.resolve(filename)))
        val fromUrl = IOUtils.toByteArray(u.openStream())
        assert(original === fromUrl)
      }
    } finally {
      deleteDatastore(datastore)
    }
  }

  it should "download a directory using a URL" in {
    def listOfFiles(dir: Path) =
      FileUtils.listFilesAndDirs(
        dir.toFile,
        TrueFileFilter.INSTANCE,
        TrueFileFilter.INSTANCE
      ).toList.sorted.map { p =>
        dir.relativize(p.toPath)
      }

    val testfilesDir = copyTestFiles
    val testfiles = listOfFiles(testfilesDir)
    val datastore = makeTestDatastore
    try {
      datastore.publishDirectory(testfilesDir, group, "TestfilesDir", 11, false)

      val urlString =
        s"datastore://${datastore.name}/$group/TestfilesDir-d11/filledDir/small_file_in_dir.bin"
      val fromUrl = IOUtils.toByteArray(new URL(urlString).openStream())

      val fromDatastore =
        IOUtils.toByteArray(
          Files.newInputStream(
            datastore.directoryPath(group, "TestfilesDir", 11).
              resolve("filledDir").
              resolve("small_file_in_dir.bin")
          )
        )

      assert(fromUrl === fromDatastore)
    } finally {
      deleteDatastore(datastore)
    }
  }
}
