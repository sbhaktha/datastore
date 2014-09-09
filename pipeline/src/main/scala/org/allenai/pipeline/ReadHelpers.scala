package org.allenai.pipeline

import spray.json.JsonFormat

import scala.reflect.ClassTag

trait ReadHelpers extends ColumnFormats {
  /** General deserialization method. */
  def readFromArtifact[T, A <: Artifact](io: ArtifactIo[T, A], artifact: A): Producer[T] = {
    require(artifact.exists, s"$artifact does not exist")
    new PersistedProducer(null, io, artifact)
  }

  /** General deserialization method. */
  def readFromArtifactProducer[T, A <: Artifact](io: ArtifactIo[T, A], src: Producer[A]): Producer[T] = new Producer[T] {
    def create = io.read(src.get)
  }

  /** Read single object from flat file */
  object ReadSingleton {
    def text[T: StringSerializable](artifact: FlatArtifact): Producer[T] =
      readFromArtifact(SingletonIo.text[T], artifact)

    def json[T: JsonFormat](artifact: FlatArtifact): Producer[T] =
      readFromArtifact(SingletonIo.json[T], artifact)
  }

  /** Read collection of type T from flat file. */
  object ReadCollection {
    def text[T: StringSerializable](artifact: FlatArtifact): Producer[Iterable[T]] =
      readFromArtifact(LineCollectionIo.text[T], artifact)

    def json[T: JsonFormat](artifact: FlatArtifact): Producer[Iterable[T]] =
      readFromArtifact(LineCollectionIo.json[T], artifact)
  }

  /** Read iterator of type T from flat file. */
  object ReadIterator {
    def text[T: StringSerializable](artifact: FlatArtifact): Producer[Iterator[T]] =
      readFromArtifact(LineIteratorIo.text[T], artifact)

    def json[T: JsonFormat](artifact: FlatArtifact): Producer[Iterator[T]] =
      readFromArtifact(LineIteratorIo.json[T], artifact)
  }

  /** Read a collection of arrays of a single type from a flat file. */
  object ReadArrayCollection {
    def text[T: StringSerializable: ClassTag](artifact: FlatArtifact, sep: Char = '\t'): Producer[Iterable[Array[T]]] =
      readFromArtifact(LineCollectionIo.text[Array[T]](columnArrayFormat[T](sep)), artifact)
  }

  /** Read an iterator of arrays of a single type from a flat file. */
  object ReadArrayIterator {
    def text[T: StringSerializable: ClassTag](artifact: FlatArtifact, sep: Char = '\t'): Producer[Iterator[Array[T]]] =
      readFromArtifact(LineIteratorIo.text[Array[T]](columnArrayFormat[T](sep)), artifact)
  }

}